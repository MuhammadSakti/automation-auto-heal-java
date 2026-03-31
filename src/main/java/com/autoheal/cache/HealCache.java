package com.autoheal.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HealCache {

    private static final String CACHE_FILE = ".autoheal-cache.json";

    private final Map<String, String> cache;
    private final File cacheFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public HealCache(boolean enabled) {
        this.enabled = enabled;
        this.cacheFile = new File(CACHE_FILE);
        this.cache = new ConcurrentHashMap<>();
        if (enabled) {
            load();
        }
    }

    public String get(String originalSelector) {
        if (!enabled) return null;
        return cache.get(originalSelector);
    }

    public void put(String originalSelector, String healedSelector) {
        if (!enabled) return;
        cache.put(originalSelector, healedSelector);
        save();
    }

    private void load() {
        if (cacheFile.exists()) {
            try {
                Map<String, String> loaded = mapper.readValue(cacheFile, new TypeReference<Map<String, String>>() {});
                cache.putAll(loaded);
            } catch (IOException e) {
                // Ignore corrupted cache
            }
        }
    }

    private void save() {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, cache);
        } catch (IOException e) {
            // Best effort
        }
    }

    public void clear() {
        cache.clear();
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
    }
}
