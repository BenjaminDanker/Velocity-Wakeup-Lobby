package com.silver.wakeup.plugin;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Persistent Velocity-level whitelist backed by a JSON file (UUIDs).
 */
public final class WhitelistStore {
    private static final String FILE_NAME = "whitelist.json";

    private final Logger logger;
    private final Path filePath;
    private final Set<UUID> uuids = new TreeSet<>();

    WhitelistStore(Path dataDir, Logger logger) {
        this.logger = logger;
        this.filePath = dataDir.resolve(FILE_NAME);
    }

    synchronized void ensureFileAndLoad() throws IOException {
        Files.createDirectories(filePath.getParent());
        if (Files.notExists(filePath)) {
            Files.writeString(filePath, "[]\n", StandardCharsets.UTF_8);
            logger.info("[WakeUpLobby] Created whitelist file at {}", filePath);
        }
        reload();
    }

    synchronized void reload() throws IOException {
        Set<UUID> loaded = new TreeSet<>();

        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            if (root instanceof Iterable<?> iterable) {
                for (Object entry : iterable) {
                    if (entry == null) {
                        continue;
                    }
                    try {
                        String uuidStr = entry.toString().trim();
                        // Handle both formats: with dashes (standard) and without dashes (manual edit)
                        UUID uuid = parseUUID(uuidStr);
                        loaded.add(uuid);
                    } catch (IllegalArgumentException e) {
                        logger.warn("[WakeUpLobby] Invalid UUID in whitelist: {}", entry);
                    }
                }
            }
        }

        uuids.clear();
        uuids.addAll(loaded);
        logger.info("[WakeUpLobby] Loaded {} whitelisted player(s)", uuids.size());
    }
    
    /**
     * Parses a UUID string that may or may not have dashes.
     * @param uuidStr UUID string (with or without dashes)
     * @return Parsed UUID
     * @throws IllegalArgumentException if the string is not a valid UUID
     */
    private UUID parseUUID(String uuidStr) throws IllegalArgumentException {
        if (uuidStr == null || uuidStr.isEmpty()) {
            throw new IllegalArgumentException("UUID string is null or empty");
        }
        
        // If it already has dashes, use standard parsing
        if (uuidStr.contains("-")) {
            return UUID.fromString(uuidStr);
        }
        
        // If no dashes and length is 32, add dashes in standard UUID format
        if (uuidStr.length() == 32) {
            String formatted = String.format("%s-%s-%s-%s-%s",
                    uuidStr.substring(0, 8),
                    uuidStr.substring(8, 12),
                    uuidStr.substring(12, 16),
                    uuidStr.substring(16, 20),
                    uuidStr.substring(20, 32));
            return UUID.fromString(formatted);
        }
        
        // Invalid format
        throw new IllegalArgumentException("Invalid UUID format: " + uuidStr);
    }

    public synchronized boolean isWhitelisted(UUID uuid) {
        return uuids.contains(uuid);
    }

    public synchronized boolean add(UUID uuid) throws IOException {
        if (uuid == null) {
            return false;
        }
        boolean changed = uuids.add(uuid);
        if (changed) {
            persist();
        }
        return changed;
    }

    public synchronized boolean remove(UUID uuid) throws IOException {
        if (uuid == null) {
            return false;
        }
        boolean changed = uuids.remove(uuid);
        if (changed) {
            persist();
        }
        return changed;
    }

    public synchronized List<UUID> list() {
        return new ArrayList<>(uuids);
    }

    private void persist() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("[\n");

        int i = 0;
        for (UUID uuid : uuids) {
            if (i++ > 0) {
                out.append(",\n");
            }
            out.append("  \"").append(uuid.toString()).append("\"");
        }

        out.append("\n]\n");
        Files.writeString(filePath, out.toString(), StandardCharsets.UTF_8);
    }
}
