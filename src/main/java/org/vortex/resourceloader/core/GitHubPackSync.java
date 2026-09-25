package org.vortex.resourceloader.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;
import org.vortex.resourceloader.config.ModConfig;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class GitHubPackSync {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/GitHubSync");
    private final ResourceLoaderMod mod;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final Map<String, CompletableFuture<File>> ongoingSyncs = new ConcurrentHashMap<>();

    public GitHubPackSync(ResourceLoaderMod mod) {
        this.mod = mod;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ResourceLoader-GitHubSync");
            t.setDaemon(true);
            return t;
        });
        this.startPeriodicSync();
    }

    private void startPeriodicSync() {
        this.scheduler.scheduleAtFixedRate(this::syncAllAutoUpdatePacks, 1, 5, TimeUnit.MINUTES);
    }

    public void syncAllAutoUpdatePacks() {
        ModConfig config = mod.getConfig();
        if (config.githubPacks == null || config.githubPacks.isEmpty()) {
            return;
        }

        for (Map.Entry<String, ModConfig.GitHubRepoSource> entry : config.githubPacks.entrySet()) {
            String packName = entry.getKey();
            ModConfig.GitHubRepoSource source = entry.getValue();
            if (source != null && source.autoUpdate && source.repo != null && !source.repo.isBlank()) {
                syncPack(packName, source, false).exceptionally(ex -> {
                    LOGGER.warn("Failed background sync for GitHub pack '{}': {}", packName, ex.getMessage());
                    return null;
                });
            }
        }
    }

    public CompletableFuture<File> syncPack(String packName, ModConfig.GitHubRepoSource source, boolean force) {
        CompletableFuture<File> active = this.ongoingSyncs.get(packName);
        if (active != null && !active.isDone()) {
            return active;
        }

        CompletableFuture<File> future = CompletableFuture.supplyAsync(() -> {
            try {
                return doSyncPack(packName, source, force);
            } catch (Exception e) {
                LOGGER.error("Error syncing GitHub pack '{}' from repo '{}': {}", packName, source.repo, e.getMessage());
                throw new CompletionException(e);
            } finally {
                this.ongoingSyncs.remove(packName);
            }
        });

        this.ongoingSyncs.put(packName, future);
        return future;
    }

    private File doSyncPack(String packName, ModConfig.GitHubRepoSource source, boolean force) throws Exception {
        String repoSlug = parseRepoSlug(source.repo);
        String branch = (source.branch != null && !source.branch.isBlank()) ? source.branch : "main";
        File destinationDir = mod.getPackManager().getResourcePackDirectory();
        File destinationZip = new File(destinationDir, packName + ".zip");

        // 1. Fetch latest commit SHA
        String commitUrl = String.format("https://api.github.com/repos/%s/commits/%s", repoSlug, branch);
        HttpRequest.Builder commitReqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(commitUrl))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ResourceLoader-Fabric-Mod")
                .timeout(Duration.ofSeconds(15));

        if (source.token != null && !source.token.isBlank()) {
            commitReqBuilder.header("Authorization", "Bearer " + source.token);
        }

        HttpResponse<String> commitResp = this.httpClient.send(commitReqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (commitResp.statusCode() != 200) {
            if (destinationZip.exists()) {
                LOGGER.warn("GitHub API returned status {} when checking commit for '{}'. Using local cached pack.", commitResp.statusCode(), packName);
                return destinationZip;
            }
            throw new IOException("GitHub API returned HTTP " + commitResp.statusCode() + ": " + commitResp.body());
        }

        JsonObject commitJson = JsonParser.parseString(commitResp.body()).getAsJsonObject();
        String latestSha = commitJson.get("sha").getAsString();

        if (!force && latestSha.equalsIgnoreCase(source.lastCommitSha) && destinationZip.exists()) {
            LOGGER.debug("GitHub pack '{}' is already up to date (commit: {})", packName, latestSha);
            return destinationZip;
        }

        LOGGER.info("Updating GitHub pack '{}' from {} (branch: {}, commit: {})", packName, repoSlug, branch, latestSha.substring(0, Math.min(7, latestSha.length())));

        // 2. Download zipball
        String zipballUrl = String.format("https://api.github.com/repos/%s/zipball/%s", repoSlug, branch);
        HttpRequest.Builder zipReqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(zipballUrl))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ResourceLoader-Fabric-Mod")
                .timeout(Duration.ofSeconds(60));

        if (source.token != null && !source.token.isBlank()) {
            zipReqBuilder.header("Authorization", "Bearer " + source.token);
        }

        HttpResponse<InputStream> zipResp = this.httpClient.send(zipReqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (zipResp.statusCode() != 200) {
            throw new IOException("Failed to download zipball from GitHub (HTTP " + zipResp.statusCode() + ")");
        }

        // 3. Extract subfolder (or root) and create local zip
        File tempZip = new File(destinationDir, packName + "_temp.zip");
        String subPath = normalizePath(source.path);

        try (InputStream in = zipResp.body();
             ZipInputStream zis = new ZipInputStream(in);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tempZip)))) {

            ZipEntry entry;
            String rootPrefix = null;
            byte[] buffer = new byte[32768];

            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // GitHub zipball root directory (e.g. "owner-repo-sha/")
                if (rootPrefix == null) {
                    int slashIdx = entryName.indexOf('/');
                    if (slashIdx != -1) {
                        rootPrefix = entryName.substring(0, slashIdx + 1);
                    }
                }

                String relativeName = entryName;
                if (rootPrefix != null && relativeName.startsWith(rootPrefix)) {
                    relativeName = relativeName.substring(rootPrefix.length());
                }

                // If a subfolder path was specified (e.g., "resourcepack/"), filter for it
                if (!subPath.isEmpty()) {
                    if (!relativeName.startsWith(subPath)) {
                        continue;
                    }
                    relativeName = relativeName.substring(subPath.length());
                    if (relativeName.startsWith("/")) {
                        relativeName = relativeName.substring(1);
                    }
                }

                if (relativeName.isEmpty()) {
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!relativeName.endsWith("/")) {
                        relativeName += "/";
                    }
                    zos.putNextEntry(new ZipEntry(relativeName));
                    zos.closeEntry();
                } else {
                    zos.putNextEntry(new ZipEntry(relativeName));
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                    zos.closeEntry();
                }
            }
        }

        Files.move(tempZip.toPath(), destinationZip.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // Update config state and clear cache
        source.lastCommitSha = latestSha;
        mod.getConfig().save(mod.getConfigDir().resolve("config.json"));
        mod.getPackManager().getHashCache().clearCache(destinationZip.getAbsolutePath());
        mod.getPackManager().loadResourcePacks(true);

        LOGGER.info("Successfully synced GitHub pack '{}' (file: {})", packName, destinationZip.getName());
        return destinationZip;
    }

    private String parseRepoSlug(String repo) {
        String clean = repo.trim();
        if (clean.startsWith("https://github.com/")) {
            clean = clean.substring("https://github.com/".length());
        } else if (clean.startsWith("http://github.com/")) {
            clean = clean.substring("http://github.com/".length());
        } else if (clean.startsWith("github.com/")) {
            clean = clean.substring("github.com/".length());
        }
        if (clean.endsWith(".git")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String clean = path.replace('\\', '/').trim();
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (!clean.isEmpty() && !clean.endsWith("/")) {
            clean += "/";
        }
        return clean;
    }

    public void shutdown() {
        this.scheduler.shutdownNow();
    }
}
