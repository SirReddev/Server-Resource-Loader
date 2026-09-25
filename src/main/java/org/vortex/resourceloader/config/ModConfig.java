package org.vortex.resourceloader.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String serverPack = "";
    public Map<String, String> resourcePacks = new LinkedHashMap<>();
    public StorageConfig storage = new StorageConfig();
    public ServerSettings server = new ServerSettings();
    public CompressionConfig compression = new CompressionConfig();
    public CacheConfig cache = new CacheConfig();
    public StatisticsConfig statistics = new StatisticsConfig();
    public EnforcementConfig enforcement = new EnforcementConfig();
    public MessagesConfig messages = new MessagesConfig();

    public static class StorageConfig {
        public String resourcePackDirectory = "";
        public boolean autoDetection = true;
    }

    public static class ServerSettings {
        public int port = 40021;
        public boolean localhost = false;
        public String address = "";
        public String fallback = "localhost";
        public boolean useHttps = false;
        public String keyStorePath = "";
        public String keyStorePassword = "";
    }

    public static class CompressionConfig {
        public boolean enabled = true;
        public boolean autoSelect = true;
        public String defaultLevel = "medium";
        public Thresholds thresholds = new Thresholds();

        public static class Thresholds {
            public int excellent = 50;
            public int good = 150;
        }
    }

    public static class CacheConfig {
        public boolean enabled = true;
        public int expiryDays = 7;
        public boolean autoCleanup = true;
    }

    public static class StatisticsConfig {
        public boolean enabled = true;
        public boolean savePreferences = true;
        public int maxPreferences = 5;
    }

    public static class EnforcementConfig {
        public boolean enabled = false;
        public boolean required = false;
        public String prompt = "This server requires a custom resource pack.";
        public boolean kickOnDecline = true;
        public boolean kickOnFail = true;
        public boolean preventInteraction = true;
        public boolean makePackPublic = false;
    }

    public static class MessagesConfig {
        public boolean enabled = true;
        public boolean showLoadingMessages = true;
        public boolean showSuccessMessages = true;
        public boolean showErrorMessages = true;
        public boolean showEnforcementMessages = true;
        public boolean showAutoloadMessages = true;
        public String prefix = "&7[&eResourceLoader&7] &r";
        public Map<String, String> customMessages = new LinkedHashMap<>();
    }

    public static ModConfig load(Path configPath) {
        File file = configPath.toFile();
        if (!file.exists()) {
            ModConfig config = new ModConfig();
            config.save(configPath);
            return config;
        }

        try (FileReader reader = new FileReader(file)) {
            ModConfig config = GSON.fromJson(reader, ModConfig.class);
            if (config == null) {
                config = new ModConfig();
            }
            if (config.messages == null) {
                config.messages = new MessagesConfig();
            }
            return config;
        } catch (Exception e) {
            LOGGER.error("Failed to load config file: {}", e.getMessage());
            return new ModConfig();
        }
    }

    public void save(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            try (FileWriter writer = new FileWriter(configPath.toFile())) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save config file: {}", e.getMessage());
        }
    }
}
