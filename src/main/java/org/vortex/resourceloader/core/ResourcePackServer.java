package org.vortex.resourceloader.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;
import org.vortex.resourceloader.config.ModConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class ResourcePackServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/Server");
    private final ResourceLoaderMod mod;
    private HttpServer server;
    private final Map<UUID, String> playerTokens = new ConcurrentHashMap<>();

    public ResourcePackServer(ResourceLoaderMod mod) {
        this.mod = mod;
    }

    public void start() {
        ModConfig config = mod.getConfig();
        int port = config.server.port;

        try {
            if (config.server.useHttps) {
                HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
                SSLContext sslContext = createSSLContext(config.server.keyStorePath, config.server.keyStorePassword);
                if (sslContext != null) {
                    httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
                    this.server = httpsServer;
                } else {
                    LOGGER.warn("Failed to initialize HTTPS context, falling back to HTTP");
                    this.server = HttpServer.create(new InetSocketAddress(port), 0);
                }
            } else {
                this.server = HttpServer.create(new InetSocketAddress(port), 0);
            }

            this.server.createContext("/download", this::handleDownload);
            this.server.createContext("/public", this::handlePublic);

            this.server.setExecutor(Executors.newFixedThreadPool(8));
            this.server.start();

            String protocol = (this.server instanceof HttpsServer) ? "https" : "http";
            String publicAddress = this.resolvePublicHost();
            LOGGER.info("Resource pack web server started on port {}", port);
            LOGGER.info("Resource pack base URL: {}://{}:{}", protocol, publicAddress, port);
        } catch (IOException e) {
            LOGGER.error("Failed to start resource pack server on port {}: {}", port, e.getMessage());
        }
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || !query.contains("token=")) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        String token = "";
        for (String param : query.split("&")) {
            if (param.startsWith("token=")) {
                token = param.substring(6);
                break;
            }
        }

        UUID authorizedPlayer = null;
        for (Map.Entry<UUID, String> entry : this.playerTokens.entrySet()) {
            if (entry.getValue().equals(token)) {
                authorizedPlayer = entry.getKey();
                break;
            }
        }

        if (authorizedPlayer == null) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        // Token is single-use for security
        this.playerTokens.remove(authorizedPlayer);

        String path = exchange.getRequestURI().getPath();
        String packPath = path.length() > "/download/".length() ? path.substring("/download/".length()) : "";
        packPath = URLDecoder.decode(packPath, StandardCharsets.UTF_8);

        serveFile(exchange, packPath);
    }

    private void handlePublic(HttpExchange exchange) throws IOException {
        if (!mod.getConfig().enforcement.makePackPublic) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String packPath = path.length() > "/public/".length() ? path.substring("/public/".length()) : "";
        packPath = URLDecoder.decode(packPath, StandardCharsets.UTF_8);

        serveFile(exchange, packPath);
    }

    private void serveFile(HttpExchange exchange, String packPath) throws IOException {
        if (packPath == null || packPath.isEmpty() || packPath.contains("..")) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        File packsDir = mod.getPackManager().getResourcePackDirectory();
        File cacheDir = mod.getPackManager().getCacheDirectory();

        Path packsRoot = packsDir.getCanonicalFile().toPath().normalize();
        Path cacheRoot = cacheDir.getCanonicalFile().toPath().normalize();

        File targetFile = new File(packsDir, packPath);
        if (!targetFile.exists()) {
            targetFile = new File(cacheDir, packPath);
        }

        Path canonicalTarget = targetFile.getCanonicalFile().toPath().normalize();
        if (!canonicalTarget.startsWith(packsRoot) && !canonicalTarget.startsWith(cacheRoot)) {
            LOGGER.warn("Blocked directory traversal attempt from {}: {}", exchange.getRemoteAddress(), packPath);
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        if (!targetFile.exists() || !targetFile.isFile()) {
            LOGGER.warn("Requested resource pack not found: {}", packPath);
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        try {
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000");
            exchange.sendResponseHeaders(200, targetFile.length());

            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(targetFile.toPath(), os);
            }
            LOGGER.info("Served resource pack '{}' ({} bytes) to {}", packPath, targetFile.length(), exchange.getRemoteAddress());
        } catch (IOException e) {
            LOGGER.warn("Failed to stream pack {}: {}", packPath, e.getMessage());
            throw e;
        } finally {
            exchange.close();
        }
    }

    public void stop() {
        if (this.server != null) {
            this.server.stop(0);
            LOGGER.info("Resource pack web server stopped");
        }
    }

    public String createDownloadURL(ServerPlayer player, String packName, String fileName) {
        String host = this.resolvePublicHost();
        int port = mod.getConfig().server.port;
        String protocol = (this.server instanceof HttpsServer) ? "https" : "http";
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        if (mod.getConfig().enforcement.makePackPublic) {
            return String.format("%s://%s:%d/public/%s", protocol, host, port, encodedName);
        }

        String token = UUID.randomUUID().toString();
        this.playerTokens.put(player.getUUID(), token);
        return String.format("%s://%s:%d/download/%s?token=%s", protocol, host, port, encodedName, token);
    }

    public String resolvePublicHost() {
        ModConfig config = mod.getConfig();
        if (config.server.address != null && !config.server.address.isBlank()) {
            return config.server.address;
        }
        if (config.server.localhost) {
            return "localhost";
        }
        try {
            String detected = InetAddress.getLocalHost().getHostAddress();
            if (detected != null && !detected.isBlank()) {
                return detected;
            }
        } catch (Exception ignored) {
        }
        return config.server.fallback != null ? config.server.fallback : "localhost";
    }

    private SSLContext createSSLContext(String keyStorePath, String keyStorePassword) {
        try {
            if (keyStorePath == null || keyStorePath.isEmpty()) {
                return null;
            }
            File ksFile = new File(keyStorePath);
            if (!ksFile.exists()) {
                LOGGER.warn("KeyStore file not found: {}", keyStorePath);
                return null;
            }

            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(ksFile)) {
                ks.load(fis, keyStorePassword != null ? keyStorePassword.toCharArray() : new char[0]);
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keyStorePassword != null ? keyStorePassword.toCharArray() : new char[0]);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
            return sslContext;
        } catch (Exception e) {
            LOGGER.error("Failed to load SSL context: {}", e.getMessage());
            return null;
        }
    }
}
