package org.vortex.resourceloader;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.commands.ResourceLoaderCommands;
import org.vortex.resourceloader.compression.PackCompressor;
import org.vortex.resourceloader.config.ModConfig;
import org.vortex.resourceloader.core.GitHubPackSync;
import org.vortex.resourceloader.core.ResourcePackManager;
import org.vortex.resourceloader.listeners.ResourcePackEnforcer;
import org.vortex.resourceloader.merger.ResourcePackMerger;
import org.vortex.resourceloader.util.MessageManager;

import java.io.File;
import java.nio.file.Path;

public class ResourceLoaderMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "resourceloader";
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader");
    private static ResourceLoaderMod instance;

    private Path configDir;
    private ModConfig config;
    private MessageManager messageManager;
    private ResourcePackManager packManager;
    private PackCompressor packCompressor;
    private ResourcePackMerger packMerger;
    private ResourcePackEnforcer enforcer;
    private GitHubPackSync gitHubSync;

    @Override
    public void onInitializeServer() {
        instance = this;
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve("resourceloader");

        File dir = this.configDir.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Initialize default packs folder
        File packsDir = this.configDir.resolve("packs").toFile();
        if (!packsDir.exists()) {
            packsDir.mkdirs();
        }

        this.config = ModConfig.load(this.configDir.resolve("config.json"));
        this.messageManager = new MessageManager(this.configDir);
        this.packCompressor = new PackCompressor(this);
        this.packMerger = new ResourcePackMerger(this);
        this.packManager = new ResourcePackManager(this);
        this.enforcer = new ResourcePackEnforcer(this);
        this.gitHubSync = new GitHubPackSync(this);

        // Register Brigadier commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            ResourceLoaderCommands.register(dispatcher);
        });

        // Register Server Play Connection events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            this.enforcer.onPlayerJoin(handler.player);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            this.enforcer.onPlayerDisconnect(handler.player);
        });

        // Register Server Shutdown lifecycle
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            this.shutdown();
        });

        LOGGER.info("ResourceLoader Fabric Mod initialized successfully!");
    }

    public void reload() {
        this.config = ModConfig.load(this.configDir.resolve("config.json"));
        this.messageManager.load();
        this.packManager.loadResourcePacks(false);
        if (this.gitHubSync != null) {
            this.gitHubSync.syncAllAutoUpdatePacks();
        }
        LOGGER.info("ResourceLoader configuration reloaded");
    }

    public void shutdown() {
        if (this.packManager != null) {
            this.packManager.shutdown();
        }
        if (this.packCompressor != null) {
            this.packCompressor.shutdown();
        }
        if (this.packMerger != null) {
            this.packMerger.shutdown();
        }
        if (this.gitHubSync != null) {
            this.gitHubSync.shutdown();
        }
        LOGGER.info("ResourceLoader has been stopped.");
    }

    public static ResourceLoaderMod getInstance() {
        return instance;
    }

    public Path getConfigDir() {
        return this.configDir;
    }

    public ModConfig getConfig() {
        return this.config;
    }

    public MessageManager getMessageManager() {
        return this.messageManager;
    }

    public ResourcePackManager getPackManager() {
        return this.packManager;
    }

    public PackCompressor getPackCompressor() {
        return this.packCompressor;
    }

    public ResourcePackMerger getPackMerger() {
        return this.packMerger;
    }

    public ResourcePackEnforcer getEnforcer() {
        return this.enforcer;
    }

    public GitHubPackSync getGitHubSync() {
        return this.gitHubSync;
    }
}
