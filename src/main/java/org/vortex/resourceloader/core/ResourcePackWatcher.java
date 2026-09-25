package org.vortex.resourceloader.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ResourcePackWatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/Watcher");
    private final ResourceLoaderMod mod;
    private final ResourcePackManager packManager;
    private WatchService watchService;
    private final ExecutorService executor;
    private volatile boolean running = true;

    public ResourcePackWatcher(ResourceLoaderMod mod, ResourcePackManager packManager) {
        this.mod = mod;
        this.packManager = packManager;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ResourceLoader-PackWatcher");
            t.setDaemon(true);
            return t;
        });

        if (mod.getConfig().storage.autoDetection) {
            this.start();
        }
    }

    public void start() {
        this.executor.submit(this::watchLoop);
    }

    private void watchLoop() {
        try {
            File packDir = packManager.getResourcePackDirectory();
            if (!packDir.exists()) {
                packDir.mkdirs();
            }

            this.watchService = FileSystems.getDefault().newWatchService();
            Path packPath = packDir.toPath();
            packPath.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    Path filename = (Path) event.context();
                    if (filename.toString().toLowerCase().endsWith(".zip")) {
                        File newFile = packPath.resolve(filename).toFile();
                        LOGGER.info("Detected new resource pack file: {}", filename);
                        // Allow file write completion
                        Thread.sleep(500);
                        packManager.handleNewResourcePack(newFile);
                    }
                }

                if (!key.reset()) {
                    break;
                }
            }
        } catch (Exception e) {
            if (running) {
                LOGGER.warn("Resource pack directory watcher stopped: {}", e.getMessage());
            }
        }
    }

    public void shutdown() {
        this.running = false;
        try {
            if (this.watchService != null) {
                this.watchService.close();
            }
        } catch (IOException ignored) {}
        this.executor.shutdownNow();
    }
}
