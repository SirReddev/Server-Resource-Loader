package org.vortex.resourceloader.compression;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class PackCompressor {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/Compressor");
    private static final int BUFFER_SIZE = 32768;
    private static final long MAX_PACK_SIZE = 100L * 1024 * 1024; // 100MB

    private final ResourceLoaderMod mod;
    private final Path cacheDir;
    private final ExecutorService compressionExecutor;
    private final Map<String, Map<CompressionLevel, File>> compressionCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<File>> activeCompressions = new ConcurrentHashMap<>();

    public PackCompressor(ResourceLoaderMod mod) {
        this.mod = mod;
        this.cacheDir = mod.getConfigDir().resolve("compression_cache");
        this.compressionExecutor = new ThreadPoolExecutor(2, 4, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        this.initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(this.cacheDir);
            this.cleanupOldCache();
        } catch (IOException e) {
            LOGGER.warn("Failed to initialize compression cache: {}", e.getMessage());
        }
    }

    public CompletableFuture<File> getCompressedPack(File originalPack, CompressionLevel level) {
        if (!mod.getConfig().compression.enabled) {
            return CompletableFuture.completedFuture(originalPack);
        }

        if (originalPack.length() > MAX_PACK_SIZE) {
            LOGGER.warn("Pack {} exceeds maximum compression limit of 100MB", originalPack.getName());
            return CompletableFuture.completedFuture(originalPack);
        }

        String packName = originalPack.getName();
        CompletableFuture<File> active = this.activeCompressions.get(packName);
        if (active != null && !active.isDone()) {
            return active;
        }

        Map<CompressionLevel, File> packCache = this.compressionCache.computeIfAbsent(packName, k -> new ConcurrentHashMap<>());
        File cachedFile = packCache.get(level);
        if (cachedFile != null && cachedFile.exists() && cachedFile.lastModified() >= originalPack.lastModified()) {
            return CompletableFuture.completedFuture(cachedFile);
        }

        CompletableFuture<File> future = CompletableFuture.supplyAsync(() -> {
            try {
                File compressed = this.compressPack(originalPack, level);
                packCache.put(level, compressed);
                return compressed;
            } catch (IOException e) {
                LOGGER.warn("Failed to compress pack {}: {}", packName, e.getMessage());
                return originalPack;
            } finally {
                this.activeCompressions.remove(packName);
            }
        }, this.compressionExecutor);

        this.activeCompressions.put(packName, future);
        return future;
    }

    private File compressPack(File originalPack, CompressionLevel level) throws IOException {
        String baseName = originalPack.getName().replaceFirst("[.][^.]+$", "");
        File compressedFile = this.cacheDir.resolve(baseName + "_" + level.name().toLowerCase() + ".zip").toFile();
        File tempFile = new File(compressedFile.getParent(), compressedFile.getName() + ".tmp");

        try (ZipFile sourceZip = new ZipFile(originalPack);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))) {
            zos.setLevel(level.getLevel());
            byte[] buffer = new byte[BUFFER_SIZE];
            int totalEntries = Collections.list(sourceZip.entries()).size();
            int processed = 0;

            Enumeration<? extends ZipEntry> entries = sourceZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setTime(entry.getTime());
                newEntry.setComment(entry.getComment());
                if (entry.getExtra() != null) {
                    newEntry.setExtra(entry.getExtra());
                }
                zos.putNextEntry(newEntry);

                if (!entry.isDirectory()) {
                    try (BufferedInputStream in = new BufferedInputStream(sourceZip.getInputStream(entry), BUFFER_SIZE)) {
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            zos.write(buffer, 0, len);
                        }
                    }
                }
                zos.closeEntry();
                processed++;

                if (processed % 50 == 0 || processed == totalEntries) {
                    LOGGER.debug("Compressing {}: {}/{} entries", originalPack.getName(), processed, totalEntries);
                }
            }
        }

        Files.move(tempFile.toPath(), compressedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return compressedFile;
    }

    public CompressionLevel getOptimalCompressionLevel(ServerPlayer player) {
        if (!mod.getConfig().compression.autoSelect) {
            return CompressionLevel.fromString(mod.getConfig().compression.defaultLevel);
        }

        int ping = player.connection != null ? player.connection.latency() : 0;
        int excellent = mod.getConfig().compression.thresholds.excellent;
        int good = mod.getConfig().compression.thresholds.good;

        if (ping < excellent) {
            return CompressionLevel.LOW;
        } else if (ping < good) {
            return CompressionLevel.MEDIUM;
        } else {
            return CompressionLevel.HIGH;
        }
    }

    private void cleanupOldCache() {
        try {
            int cacheDays = mod.getConfig().cache.expiryDays;
            long expiryTime = System.currentTimeMillis() - ((long) cacheDays * 24 * 60 * 60 * 1000L);

            if (Files.exists(this.cacheDir)) {
                try (var stream = Files.walk(this.cacheDir)) {
                    stream.filter(Files::isRegularFile).forEach(path -> {
                        try {
                            if (Files.getLastModifiedTime(path).toMillis() < expiryTime) {
                                Files.delete(path);
                            }
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to cleanup compression cache: {}", e.getMessage());
        }
    }

    public void shutdown() {
        this.activeCompressions.values().forEach(f -> f.cancel(true));
        this.activeCompressions.clear();
        this.compressionExecutor.shutdown();
        try {
            if (!this.compressionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.compressionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.compressionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public enum CompressionLevel {
        LOW(1),
        MEDIUM(6),
        HIGH(9);

        private final int level;

        CompressionLevel(int level) {
            this.level = level;
        }

        public int getLevel() {
            return this.level;
        }

        public static CompressionLevel fromString(String level) {
            try {
                return CompressionLevel.valueOf(level.toUpperCase());
            } catch (Exception e) {
                return MEDIUM;
            }
        }
    }
}
