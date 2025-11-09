package com.silver.wakeup.state;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Handles loading and saving per-player state (last server, visited servers).
 */
public class PlayerStateStore {
    private final Path lastServerPath;
    private final Path visitedPath;
    private final Logger logger;

    public PlayerStateStore(Path dataDir, Logger logger) {
        this.lastServerPath = dataDir.resolve("last-server.properties");
        this.visitedPath = dataDir.resolve("visited.properties");
        this.logger = logger;
    }

    public Map<UUID, String> loadLastServers() {
        Properties props = loadProperties(lastServerPath);
        Map<UUID, String> result = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            try {
                UUID id = UUID.fromString(key);
                result.put(id, props.getProperty(key));
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
        return result;
    }

    public Map<UUID, Set<String>> loadVisitedServers() {
        Properties props = loadProperties(visitedPath);
        Map<UUID, Set<String>> result = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            try {
                UUID id = UUID.fromString(key);
                Set<String> servers = new HashSet<>(Arrays.asList(props.getProperty(key, "").split(",")));
                servers.removeIf(String::isBlank);
                result.put(id, servers);
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
        return result;
    }

    public void saveLastServers(Map<UUID, String> lastServer) {
        Properties props = new Properties();
        lastServer.forEach((id, name) -> props.setProperty(id.toString(), name));
        storeProperties(lastServerPath, props, "WakeUpLobby last-server map");
    }

    public void saveVisitedServers(Map<UUID, Set<String>> visited) {
        Properties props = new Properties();
        visited.forEach((id, servers) -> props.setProperty(id.toString(), String.join(",", servers)));
        storeProperties(visitedPath, props, "servers visited per player");
    }

    public boolean purgeHoldingFromLastServers(Map<UUID, String> lastServer, String holdingServer) {
        return lastServer.entrySet().removeIf(entry -> holdingServer.equalsIgnoreCase(entry.getValue()));
    }

    public boolean purgeHoldingFromVisited(Map<UUID, Set<String>> visited, String holdingServer) {
        boolean changed = false;
        for (var entry : visited.entrySet()) {
            Set<String> servers = entry.getValue();
            if (servers.removeIf(s -> holdingServer.equalsIgnoreCase(s))) {
                changed = true;
            }
        }
        return changed;
    }

    private Properties loadProperties(Path path) {
        Properties props = new Properties();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ex) {
                logger.warn("Failed to load {}", path, ex);
            }
        }
        return props;
    }

    private void storeProperties(Path path, Properties props, String comment) {
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, comment);
            }
        } catch (IOException ex) {
            logger.warn("Failed to write {}", path, ex);
        }
    }
}
