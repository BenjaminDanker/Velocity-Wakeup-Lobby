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
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Persistent Velocity-level ops list backed by a JSON file.
 */
final class VelocityOpsStore {
    private static final String FILE_NAME = "velocity-ops.json";

    private final Logger logger;
    private final Path filePath;
    private final Set<String> usernames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    VelocityOpsStore(Path dataDir, Logger logger) {
        this.logger = logger;
        this.filePath = dataDir.resolve(FILE_NAME);
    }

    synchronized void ensureFileAndLoad() throws IOException {
        Files.createDirectories(filePath.getParent());
        if (Files.notExists(filePath)) {
            Files.writeString(filePath, "[]\n", StandardCharsets.UTF_8);
            logger.info("[WakeUpLobby] Created velocity ops file at {}", filePath);
        }
        reload();
    }

    synchronized void reload() throws IOException {
        Set<String> loaded = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            if (root instanceof Iterable<?> iterable) {
                for (Object entry : iterable) {
                    if (entry == null) {
                        continue;
                    }
                    String username = normalize(entry.toString());
                    if (!username.isBlank()) {
                        loaded.add(username);
                    }
                }
            }
        }

        usernames.clear();
        usernames.addAll(loaded);
        logger.info("[WakeUpLobby] Loaded {} velocity op(s)", usernames.size());
    }

    synchronized boolean isVelocityOp(String username) {
        return usernames.contains(normalize(username));
    }

    synchronized boolean add(String username) throws IOException {
        String normalized = normalize(username);
        if (normalized.isBlank()) {
            return false;
        }
        boolean changed = usernames.add(normalized);
        if (changed) {
            persist();
        }
        return changed;
    }

    synchronized boolean remove(String username) throws IOException {
        String normalized = normalize(username);
        if (normalized.isBlank()) {
            return false;
        }
        boolean changed = usernames.remove(normalized);
        if (changed) {
            persist();
        }
        return changed;
    }

    synchronized List<String> list() {
        return new ArrayList<>(usernames);
    }

    private void persist() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("[\n");

        int i = 0;
        for (String username : usernames) {
            if (i++ > 0) {
                out.append(",\n");
            }
            out.append("  \"").append(escapeJson(username)).append("\"");
        }

        out.append("\n]\n");
        Files.writeString(filePath, out.toString(), StandardCharsets.UTF_8);
    }

    private static String normalize(String username) {
        if (username == null) {
            return "";
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
