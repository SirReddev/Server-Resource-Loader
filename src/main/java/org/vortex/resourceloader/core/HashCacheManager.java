package org.vortex.resourceloader.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.util.FileUtil;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HashCacheManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/HashCache");
    private final ConcurrentHashMap<String, CachedHash> hashCache = new ConcurrentHashMap<>();

    public HashCacheManager() {
    }

    public String getOrCalculateHash(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        String filePath = file.getAbsolutePath();
        long lastModified = file.lastModified();
        CachedHash cached = this.hashCache.get(filePath);
        if (cached != null && cached.lastModified == lastModified) {
            return cached.hash;
        }

        byte[] hashBytes = FileUtil.calcSHA1(file);
        if (hashBytes != null) {
            String hash = FileUtil.bytesToHex(hashBytes);
            this.hashCache.put(filePath, new CachedHash(hash, lastModified));
            return hash;
        }
        return null;
    }

    public byte[] getOrCalculateHashBytes(File file) {
        String hex = getOrCalculateHash(file);
        return hex != null ? FileUtil.hexToBytes(hex) : null;
    }

    public boolean haveSameHash(File file1, File file2) {
        String hash1 = this.getOrCalculateHash(file1);
        String hash2 = this.getOrCalculateHash(file2);
        return hash1 != null && hash1.equals(hash2);
    }

    public boolean hasUrlChanged(String url, String currentHash) {
        CachedHash cached = this.hashCache.get("url:" + url);
        return cached == null || !cached.hash.equals(currentHash);
    }

    public void cacheUrlHash(String url, String hash) {
        this.hashCache.put("url:" + url, new CachedHash(hash, System.currentTimeMillis()));
    }

    public void clearCache(String identifier) {
        this.hashCache.remove(identifier);
    }

    public void clearAllCache() {
        this.hashCache.clear();
        LOGGER.info("Hash cache cleared");
    }

    public void cleanupExpiredEntries() {
        long cutoffTime = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000L);
        int removed = 0;
        for (Map.Entry<String, CachedHash> entry : this.hashCache.entrySet()) {
            if (entry.getKey().startsWith("url:") && entry.getValue().lastModified < cutoffTime) {
                this.hashCache.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("Cleaned up {} expired hash cache entries", removed);
        }
    }

    private static class CachedHash {
        final String hash;
        final long lastModified;

        CachedHash(String hash, long lastModified) {
            this.hash = hash;
            this.lastModified = lastModified;
        }
    }
}
