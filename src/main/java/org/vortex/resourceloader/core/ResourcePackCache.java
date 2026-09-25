package org.vortex.resourceloader.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ResourcePackCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/Cache");
    private final ResourceLoaderMod mod;
    private final Path cacheDir;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CompletableFuture<File>> activeDownloads = new ConcurrentHashMap<>();

    public ResourcePackCache(ResourceLoaderMod mod) {
        this.mod = mod;
        this.cacheDir = mod.getConfigDir().resolve("cache");
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        try {
            Files.createDirectories(this.cacheDir);
            this.cleanupExpiredCache();
        } catch (IOException e) {
            LOGGER.warn("Failed to initialize cache directory: {}", e.getMessage());
        }
    }

    public CompletableFuture<File> getCachedPack(String url, String packName) {
        if (!mod.getConfig().cache.enabled) {
            return this.downloadDirectly(url, packName);
        }

        File cachedFile = this.cacheDir.resolve(packName + ".zip").toFile();
        if (cachedFile.exists()) {
            return CompletableFuture.completedFuture(cachedFile);
        }

        return this.activeDownloads.computeIfAbsent(packName, k -> {
            LOGGER.info("Downloading remote resource pack '{}' from {}", packName, url);
            return downloadDirectly(url, packName).whenComplete((f, err) -> activeDownloads.remove(k));
        });
    }

    private CompletableFuture<File> downloadDirectly(String url, String packName) {
        CompletableFuture<File> future = new CompletableFuture<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "ResourceLoader-Fabric/2.4.0")
                    .GET()
                    .build();

            Path tempTarget = this.cacheDir.resolve(packName + ".tmp");
            this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofFile(tempTarget))
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            File targetFile = this.cacheDir.resolve(packName + ".zip").toFile();
                            try {
                                Files.move(tempTarget, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                LOGGER.info("Downloaded resource pack '{}' ({} bytes)", packName, targetFile.length());
                                future.complete(targetFile);
                            } catch (IOException e) {
                                future.completeExceptionally(e);
                            }
                        } else {
                            try {
                                Files.deleteIfExists(tempTarget);
                            } catch (IOException ignored) {}
                            future.completeExceptionally(new IOException("HTTP response code: " + response.statusCode()));
                        }
                    })
                    .exceptionally(ex -> {
                        try {
                            Files.deleteIfExists(tempTarget);
                        } catch (IOException ignored) {}
                        future.completeExceptionally(ex);
                        return null;
                    });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public void clearCache() {
        try {
            if (Files.exists(this.cacheDir)) {
                try (var stream = Files.walk(this.cacheDir)) {
                    stream.filter(Files::isRegularFile).forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {}
                    });
                }
            }
            LOGGER.info("Resource pack cache cleared successfully");
        } catch (IOException e) {
            LOGGER.warn("Failed to clear cache: {}", e.getMessage());
        }
    }

    private void cleanupExpiredCache() {
        if (!mod.getConfig().cache.autoCleanup) return;
        try {
            int expiryDays = mod.getConfig().cache.expiryDays;
            long cutoffTime = System.currentTimeMillis() - ((long) expiryDays * 24 * 60 * 60 * 1000L);
            if (Files.exists(this.cacheDir)) {
                try (var stream = Files.walk(this.cacheDir)) {
                    stream.filter(Files::isRegularFile).forEach(path -> {
                        try {
                            if (Files.getLastModifiedTime(path).toMillis() < cutoffTime) {
                                Files.delete(path);
                            }
                        } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to clean up expired cache: {}", e.getMessage());
        }
    }
}
