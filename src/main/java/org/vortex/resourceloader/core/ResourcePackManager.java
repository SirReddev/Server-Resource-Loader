package org.vortex.resourceloader.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;
import org.vortex.resourceloader.compression.PackCompressor;
import org.vortex.resourceloader.config.ModConfig;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ResourcePackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/PackManager");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ResourceLoaderMod mod;
    private final Map<String, File> resourcePacks = new ConcurrentHashMap<>();
    private final ResourcePackCache packCache;
    private final ResourcePackServer packServer;
    private final ResourcePackWatcher packWatcher;
    private final HashCacheManager hashCache;
    private final Map<UUID, List<String>> playerPreferences = new ConcurrentHashMap<>();
    private final Path preferencesFile;

    public ResourcePackManager(ResourceLoaderMod mod) {
        this.mod = mod;
        this.packCache = new ResourcePackCache(mod);
        this.packServer = new ResourcePackServer(mod);
        this.hashCache = new HashCacheManager();
        this.preferencesFile = mod.getConfigDir().resolve("preferences.json");

        this.loadPreferences();
        this.loadResourcePacks(true);
        this.packServer.start();
        this.packWatcher = new ResourcePackWatcher(mod, this);
    }

    private void loadPreferences() {
        if (!Files.exists(this.preferencesFile)) {
            return;
        }
        try (FileReader reader = new FileReader(this.preferencesFile.toFile())) {
            Map<String, List<String>> raw = GSON.fromJson(reader, new TypeToken<Map<String, List<String>>>() {}.getType());
            if (raw != null) {
                raw.forEach((k, v) -> {
                    try {
                        this.playerPreferences.put(UUID.fromString(k), new ArrayList<>(v));
                    } catch (IllegalArgumentException ignored) {}
                });
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load player preferences: {}", e.getMessage());
        }
    }

    public void savePreferences() {
        try {
            Files.createDirectories(this.preferencesFile.getParent());
            Map<String, List<String>> raw = new HashMap<>();
            this.playerPreferences.forEach((k, v) -> raw.put(k.toString(), v));
            try (FileWriter writer = new FileWriter(this.preferencesFile.toFile())) {
                GSON.toJson(raw, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save player preferences: {}", e.getMessage());
        }
    }

    public List<String> getPlayerPreferences(UUID playerId) {
        return this.playerPreferences.getOrDefault(playerId, Collections.emptyList());
    }

    public void setPlayerPreference(UUID playerId, String packName) {
        List<String> preferences = this.playerPreferences.computeIfAbsent(playerId, k -> new ArrayList<>());
        preferences.remove(packName);
        preferences.add(0, packName);
        int max = mod.getConfig().statistics.maxPreferences;
        if (preferences.size() > max) {
            preferences.subList(max, preferences.size()).clear();
        }
        this.savePreferences();
    }

    public void clearPlayerPreferences(UUID playerId) {
        this.playerPreferences.remove(playerId);
        this.savePreferences();
    }

    public void loadResourcePacks(boolean silent) {
        this.resourcePacks.clear();
        File packDirectory = this.getResourcePackDirectory();
        if (!packDirectory.exists()) {
            packDirectory.mkdirs();
        }

        ModConfig config = mod.getConfig();
        String serverPack = config.serverPack;
        if (serverPack != null && !serverPack.isBlank()) {
            if (serverPack.startsWith("http://") || serverPack.startsWith("https://")) {
                this.resourcePacks.put("server", null);
                if (!silent) LOGGER.info("Registered default remote server pack: {}", serverPack);
            } else {
                File serverPackFile = new File(packDirectory, serverPack);
                if (serverPackFile.exists()) {
                    this.resourcePacks.put("server", serverPackFile);
                    if (!silent) LOGGER.info("Loaded default server pack: {}", serverPack);
                } else {
                    LOGGER.warn("Configured default server pack file not found: {}", serverPack);
                }
            }
        }

        if (config.resourcePacks != null) {
            for (Map.Entry<String, String> entry : config.resourcePacks.entrySet()) {
                String key = entry.getKey().toLowerCase();
                String pathOrUrl = entry.getValue();
                if (pathOrUrl != null && !pathOrUrl.startsWith("http")) {
                    File packFile = new File(packDirectory, pathOrUrl);
                    if (packFile.exists()) {
                        this.resourcePacks.put(key, packFile);
                        if (!silent) LOGGER.info("Loaded resource pack: {}", key);
                    } else {
                        LOGGER.warn("Resource pack file not found for '{}': {}", key, pathOrUrl);
                    }
                } else if (pathOrUrl != null) {
                    this.resourcePacks.put(key, null);
                    if (!silent) LOGGER.info("Registered external resource pack URL: {}", key);
                }
            }
        }

        if (config.githubPacks != null) {
            for (Map.Entry<String, ModConfig.GitHubRepoSource> entry : config.githubPacks.entrySet()) {
                String key = entry.getKey().toLowerCase();
                File packFile = new File(packDirectory, key + ".zip");
                if (packFile.exists()) {
                    this.resourcePacks.put(key, packFile);
                    if (!silent) LOGGER.info("Loaded GitHub-synced pack: {}", key);
                } else {
                    this.resourcePacks.put(key, null);
                    if (!silent) LOGGER.info("Registered GitHub-synced pack source: {}", key);
                }
            }
        }

        // Auto-discover any .zip files present in the packs folder
        File[] files = packDirectory.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (files != null) {
            for (File file : files) {
                String name = sanitizePackName(file.getName());
                if (!this.resourcePacks.containsKey(name) && !file.getName().equalsIgnoreCase(config.serverPack)) {
                    this.resourcePacks.put(name, file);
                    if (!silent) LOGGER.info("Auto-discovered resource pack: {} ({})", name, file.getName());
                }
            }
        }
    }

    public void sendResourcePack(ServerPlayer player, String packName) {
        if (packName == null || packName.isEmpty()) {
            mod.getMessageManager().sendError(player, "resource-packs.no-default");
            return;
        }

        String lowerName = packName.toLowerCase();

        // Handle GitHub repo pack on-demand sync if file is missing
        if (mod.getConfig().githubPacks != null && mod.getConfig().githubPacks.containsKey(lowerName)) {
            ModConfig.GitHubRepoSource source = mod.getConfig().githubPacks.get(lowerName);
            File localZip = new File(getResourcePackDirectory(), lowerName + ".zip");
            if (!localZip.exists()) {
                mod.getMessageManager().sendLoading(player, packName);
                mod.getGitHubSync().syncPack(lowerName, source, true).thenAcceptAsync(file -> {
                    this.resourcePacks.put(lowerName, file);
                    sendLocalPackFile(player, lowerName, file);
                }).exceptionally(ex -> {
                    mod.getMessageManager().sendError(player, "resource-packs.load-failed", "error", ex.getMessage());
                    return null;
                });
                return;
            }
        }

        String packPathOrUrl = null;

        if (lowerName.equals("server")) {
            packPathOrUrl = mod.getConfig().serverPack;
        } else if (mod.getConfig().resourcePacks.containsKey(lowerName)) {
            packPathOrUrl = mod.getConfig().resourcePacks.get(lowerName);
        } else if (this.resourcePacks.containsKey(lowerName)) {
            File packFile = this.resourcePacks.get(lowerName);
            if (packFile != null) {
                packPathOrUrl = packFile.getName();
            }
        }

        if (packPathOrUrl == null || packPathOrUrl.isBlank()) {
            mod.getMessageManager().sendError(player, "resource-packs.not-found", "pack", packName);
            return;
        }

        mod.getMessageManager().sendLoading(player, packName);

        UUID packUuid = UUID.nameUUIDFromBytes(("resourceloader:" + lowerName).getBytes(StandardCharsets.UTF_8));
        boolean required = mod.getConfig().enforcement.required;
        Optional<Component> prompt = Optional.ofNullable(mod.getConfig().enforcement.prompt != null && !mod.getConfig().enforcement.prompt.isEmpty()
                ? Component.literal(mod.getConfig().enforcement.prompt) : null);

        if (packPathOrUrl.startsWith("http://") || packPathOrUrl.startsWith("https://")) {
            String finalUrl = packPathOrUrl;
            this.packCache.getCachedPack(finalUrl, lowerName).thenAcceptAsync(cachedFile -> {
                try {
                    String sha1 = hashCache.getOrCalculateHash(cachedFile);
                    String downloadUrl = this.packServer.createDownloadURL(player, lowerName, cachedFile.getName());
                    sendPacketToPlayer(player, packUuid, downloadUrl, sha1 != null ? sha1 : "", required, prompt);
                    mod.getMessageManager().sendSuccess(player, "resource-packs.load-success");
                } catch (Exception e) {
                    mod.getMessageManager().sendError(player, "resource-packs.load-failed", "error", e.getMessage());
                    LOGGER.error("Failed to serve cached pack: {}", e.getMessage());
                }
            }).exceptionally(ex -> {
                mod.getMessageManager().sendError(player, "resource-packs.load-failed", "error", ex.getMessage());
                LOGGER.error("Failed to download remote pack '{}': {}", lowerName, ex.getMessage());
                return null;
            });
        } else {
            File packFile = this.resourcePacks.get(lowerName);
            sendLocalPackFile(player, lowerName, packFile);
        }
    }

    public void sendLocalPackFile(ServerPlayer player, String lowerName, File packFile) {
        if (packFile == null || !packFile.exists()) {
            mod.getMessageManager().sendError(player, "resource-packs.file-not-found");
            return;
        }

        UUID packUuid = UUID.nameUUIDFromBytes(("resourceloader:" + lowerName).getBytes(StandardCharsets.UTF_8));
        boolean required = mod.getConfig().enforcement.required;
        Optional<Component> prompt = Optional.ofNullable(mod.getConfig().enforcement.prompt != null && !mod.getConfig().enforcement.prompt.isEmpty()
                ? Component.literal(mod.getConfig().enforcement.prompt) : null);

        PackCompressor.CompressionLevel level = mod.getPackCompressor().getOptimalCompressionLevel(player);
        mod.getPackCompressor().getCompressedPack(packFile, level).thenAcceptAsync(finalFile -> {
            try {
                String sha1 = hashCache.getOrCalculateHash(finalFile);
                String downloadUrl = this.packServer.createDownloadURL(player, lowerName, finalFile.getName());
                sendPacketToPlayer(player, packUuid, downloadUrl, sha1 != null ? sha1 : "", required, prompt);
            } catch (Exception e) {
                mod.getMessageManager().sendError(player, "resource-packs.load-failed", "error", e.getMessage());
                LOGGER.error("Failed to send pack to player: {}", e.getMessage());
            }
        });
    }

    private void sendPacketToPlayer(ServerPlayer player, UUID packUuid, String url, String sha1, boolean required, Optional<Component> prompt) {
        if (player.connection == null) return;
        ClientboundResourcePackPushPacket packet = new ClientboundResourcePackPushPacket(packUuid, url, sha1, required, prompt);
        player.connection.send(packet);
        LOGGER.info("Sent resource pack packet to {} (UUID: {}, URL: {})", player.getScoreboardName(), packUuid, url);
    }

    public void unloadResourcePack(ServerPlayer player, String packName) {
        if (player.connection == null) return;
        try {
            if (packName == null || packName.isBlank() || packName.equalsIgnoreCase("all")) {
                player.connection.send(new ClientboundResourcePackPopPacket(Optional.empty()));
                mod.getMessageManager().sendSuccess(player, "resource-packs.unloaded");
                LOGGER.info("Unloaded all server resource packs for player {}", player.getScoreboardName());
            } else {
                UUID packUuid = UUID.nameUUIDFromBytes(("resourceloader:" + packName.toLowerCase()).getBytes(StandardCharsets.UTF_8));
                player.connection.send(new ClientboundResourcePackPopPacket(Optional.of(packUuid)));
                mod.getMessageManager().sendSuccess(player, "resource-packs.unloaded", "pack", packName);
                LOGGER.info("Unloaded resource pack '{}' ({}) for player {}", packName, packUuid, player.getScoreboardName());
            }
        } catch (Exception e) {
            mod.getMessageManager().sendError(player, "resource-packs.unload-failed", "error", e.getMessage());
            LOGGER.error("Failed to unload resource pack for player {}: {}", player.getScoreboardName(), e.getMessage());
        }
    }

    public File getResourcePackDirectory() {
        String customPath = mod.getConfig().storage.resourcePackDirectory;
        if (customPath != null && !customPath.isBlank()) {
            File customDir = new File(customPath);
            if (customDir.exists() || customDir.mkdirs()) {
                return customDir;
            }
            LOGGER.warn("Failed to use custom resource pack directory: {}", customPath);
        }
        return mod.getConfigDir().resolve("packs").toFile();
    }

    public File getCacheDirectory() {
        return mod.getConfigDir().resolve("cache").toFile();
    }

    public Map<String, File> getResourcePacks() {
        return this.resourcePacks;
    }

    public ResourcePackCache getPackCache() {
        return this.packCache;
    }

    public ResourcePackServer getPackServer() {
        return this.packServer;
    }

    public HashCacheManager getHashCache() {
        return this.hashCache;
    }

    public void shutdown() {
        this.packServer.stop();
        this.packWatcher.shutdown();
        this.savePreferences();
    }

    public String sanitizePackName(String packName) {
        if (packName.toLowerCase().endsWith(".zip")) {
            packName = packName.substring(0, packName.length() - 4);
        }
        packName = packName.toLowerCase();
        packName = packName.replaceAll("[\\s.]+", "_");
        packName = packName.replaceAll("[^a-z0-9_]", "");
        packName = packName.replaceAll("_+", "_");
        packName = packName.replaceAll("^_+|_+$", "");
        if (packName.isEmpty()) {
            packName = "resource_pack";
        }
        return packName;
    }

    public void handleNewResourcePack(File packFile) {
        if (!packFile.getName().toLowerCase().endsWith(".zip")) {
            return;
        }
        String originalName = packFile.getName();
        String sanitizedName = this.sanitizePackName(originalName);
        File packDirectory = this.getResourcePackDirectory();
        File newFile = new File(packDirectory, sanitizedName + ".zip");

        try {
            if (packFile.getName().equals(sanitizedName + ".zip") && packFile.getParentFile().equals(packDirectory)) {
                newFile = packFile;
            } else {
                int counter = 1;
                while (newFile.exists()) {
                    String nextName = sanitizedName + "_" + counter;
                    newFile = new File(packDirectory, nextName + ".zip");
                    counter++;
                }
                Files.move(packFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            this.resourcePacks.put(sanitizedName, newFile);
            LOGGER.info("Registered new resource pack: {} (file: {})", sanitizedName, newFile.getName());
        } catch (IOException e) {
            LOGGER.warn("Failed to process new resource pack {}: {}", packFile.getName(), e.getMessage());
        }
    }
}
