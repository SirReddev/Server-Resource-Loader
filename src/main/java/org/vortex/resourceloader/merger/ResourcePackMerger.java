package org.vortex.resourceloader.merger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;
import org.vortex.resourceloader.util.FileUtil;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ResourcePackMerger {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/Merger");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int BUFFER_SIZE = 32768;

    private final ResourceLoaderMod mod;
    private final ExecutorService executor;
    private final Set<File> pendingCleanup = ConcurrentHashMap.newKeySet();

    public ResourcePackMerger(ResourceLoaderMod mod) {
        this.mod = mod;
        this.executor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public File mergeResourcePacks(List<File> inputPacks, String outputName) throws IOException {
        if (inputPacks.isEmpty()) {
            throw new IllegalArgumentException("No input packs provided");
        }

        if (!outputName.toLowerCase().endsWith(".zip")) {
            outputName = outputName + ".zip";
        }

        File workDir = new File(mod.getConfigDir().toFile(), "temp/merge_" + System.currentTimeMillis());
        workDir.mkdirs();
        this.pendingCleanup.add(workDir);

        try {
            LOGGER.info("Merging {} resource packs into {}...", inputPacks.size(), outputName);

            List<CompletableFuture<File>> extractFutures = new ArrayList<>();
            for (File file : inputPacks) {
                extractFutures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return this.extractPack(file, workDir);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, this.executor));
            }

            List<File> extractedDirs = new ArrayList<>();
            for (CompletableFuture<File> future : extractFutures) {
                try {
                    extractedDirs.add(future.join());
                } catch (Exception e) {
                    throw new IOException("Failed to extract pack: " + e.getMessage(), e);
                }
            }

            File mergedDir = new File(workDir, "merged");
            mergedDir.mkdirs();

            for (int i = 0; i < extractedDirs.size(); i++) {
                File sourceDir = extractedDirs.get(i);
                boolean isLast = (i == extractedDirs.size() - 1);
                this.mergeDirectory(sourceDir, mergedDir, isLast);
            }

            this.updatePackMeta(mergedDir);

            File tmpZip = new File(workDir, outputName + ".tmp");
            this.zipDirectory(mergedDir, tmpZip);
            FileUtil.validateZipFile(tmpZip);

            File finalOutputFile = new File(mod.getPackManager().getResourcePackDirectory(), outputName);
            finalOutputFile.getParentFile().mkdirs();
            Files.move(tmpZip.toPath(), finalOutputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            LOGGER.info("Successfully merged packs into {}", finalOutputFile.getName());
            return finalOutputFile;
        } finally {
            this.cleanup(workDir);
        }
    }

    private File extractPack(File pack, File workDir) throws IOException {
        if (!pack.getName().toLowerCase().endsWith(".zip")) {
            return pack;
        }

        File extractDir = new File(workDir, pack.getName().replace(".zip", ""));
        try (ZipFile zipFile = new ZipFile(pack)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            byte[] buffer = new byte[BUFFER_SIZE];

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryFile = new File(extractDir, entry.getName());
                if (entry.isDirectory()) {
                    entryFile.mkdirs();
                    continue;
                }
                entryFile.getParentFile().mkdirs();
                try (InputStream in = zipFile.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(entryFile)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        return extractDir;
    }

    private void mergeDirectory(File sourceDir, File targetDir, boolean isLastPack) throws IOException {
        if (!sourceDir.exists()) return;

        try (var stream = Files.walk(sourceDir.toPath())) {
            stream.filter(Files::isRegularFile).forEach(sourcePath -> {
                try {
                    Path relativePath = sourceDir.toPath().relativize(sourcePath);
                    File targetFile = new File(targetDir, relativePath.toString());

                    if (sourcePath.toString().endsWith(".json")) {
                        this.mergeJsonFile(targetFile, sourcePath.toFile(), isLastPack);
                    } else if (isLastPack || !targetFile.exists()) {
                        targetFile.getParentFile().mkdirs();
                        Files.copy(sourcePath, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to merge file {}: {}", sourcePath, e.getMessage());
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeJsonFile(File targetFile, File sourceFile, boolean isLastPack) throws IOException {
        Map<String, Object> sourceMap = this.readJsonFile(sourceFile);
        if (sourceMap == null) return;

        if (!targetFile.exists() || isLastPack) {
            targetFile.getParentFile().mkdirs();
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        Map<String, Object> targetMap = this.readJsonFile(targetFile);
        if (targetMap == null) {
            targetFile.getParentFile().mkdirs();
            Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        if (isModelFile(targetFile)) {
            mergeModelFile(targetMap, sourceMap);
        } else if (isLanguageFile(targetFile) || isSoundsFile(targetFile)) {
            targetMap.putAll(sourceMap);
        } else {
            deepMerge(targetMap, sourceMap);
        }

        this.writeJsonFile(targetFile, targetMap);
    }

    private boolean isModelFile(File file) {
        String path = file.getPath().toLowerCase();
        return path.contains("models") || path.contains("blockstates") || path.endsWith(".model.json");
    }

    private boolean isLanguageFile(File file) {
        return file.getPath().toLowerCase().contains("lang");
    }

    private boolean isSoundsFile(File file) {
        return file.getName().equalsIgnoreCase("sounds.json");
    }

    @SuppressWarnings("unchecked")
    private void mergeModelFile(Map<String, Object> target, Map<String, Object> source) {
        if (source.containsKey("parent")) {
            target.put("parent", source.get("parent"));
        }
        if (source.containsKey("textures") && source.get("textures") instanceof Map) {
            Map<String, Object> targetTextures = (Map<String, Object>) target.computeIfAbsent("textures", k -> new HashMap<>());
            targetTextures.putAll((Map<String, Object>) source.get("textures"));
        }
        if (source.containsKey("elements") && source.get("elements") instanceof List) {
            List<Object> targetElements = (List<Object>) target.computeIfAbsent("elements", k -> new ArrayList<>());
            targetElements.addAll((List<Object>) source.get("elements"));
        }
        if (source.containsKey("display")) {
            target.put("display", source.get("display"));
        }
        if (source.containsKey("overrides") && source.get("overrides") instanceof List) {
            List<Object> targetOverrides = (List<Object>) target.computeIfAbsent("overrides", k -> new ArrayList<>());
            List<Object> sourceOverrides = (List<Object>) source.get("overrides");
            Set<String> existing = new HashSet<>();
            targetOverrides.forEach(o -> existing.add(o.toString()));
            for (Object override : sourceOverrides) {
                if (!existing.contains(override.toString())) {
                    targetOverrides.add(override);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (String key : source.keySet()) {
            Object sourceValue = source.get(key);
            if (sourceValue instanceof Map) {
                Map<String, Object> targetSubMap = (Map<String, Object>) target.computeIfAbsent(key, k -> new HashMap<>());
                deepMerge(targetSubMap, (Map<String, Object>) sourceValue);
            } else {
                target.put(key, sourceValue);
            }
        }
    }

    private Map<String, Object> readJsonFile(File file) {
        try (FileReader reader = new FileReader(file)) {
            return GSON.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
        } catch (Exception e) {
            LOGGER.warn("Failed to parse JSON file {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    private void writeJsonFile(File file, Map<String, Object> content) throws IOException {
        file.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(content, writer);
        }
    }

    private void zipDirectory(File sourceDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            try (var stream = Files.walk(sourceDir.toPath())) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    String relative = sourceDir.toPath().relativize(path).toString().replace('\\', '/');
                    ZipEntry entry = new ZipEntry(relative);
                    try {
                        zos.putNextEntry(entry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        LOGGER.warn("Failed to write entry to zip {}: {}", relative, e.getMessage());
                    }
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updatePackMeta(File packDir) throws IOException {
        File mcmetaFile = new File(packDir, "pack.mcmeta");
        Map<String, Object> mcmeta = mcmetaFile.exists() ? readJsonFile(mcmetaFile) : new HashMap<>();
        if (mcmeta == null) mcmeta = new HashMap<>();

        Map<String, Object> packSection = (Map<String, Object>) mcmeta.computeIfAbsent("pack", k -> new HashMap<>());
        int packFormat = 46; // Minecraft 1.21.4 default pack format
        packSection.put("pack_format", packFormat);
        packSection.put("description", "Merged Resource Pack (Format " + packFormat + ")");

        writeJsonFile(mcmetaFile, mcmeta);
    }

    private void cleanup(File workDir) {
        CompletableFuture.runAsync(() -> {
            try {
                if (workDir != null && workDir.exists()) {
                    deleteRecursively(workDir);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to delete temp dir: {}", e.getMessage());
            } finally {
                this.pendingCleanup.remove(workDir);
            }
        }, this.executor);
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    public void shutdown() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
