package com.silver.wakeup.config;

import com.silver.wakeup.session.StickyRouter;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Handles reading and writing WakeUpLobby configuration from disk.
 */
public class LobbyConfigLoader {
    private static final String CONFIG_FILE_NAME = "config.yml";

    private final Logger log;
    private final Path dataDir;

    public LobbyConfigLoader(Logger log, Path dataDir) {
        this.log = Objects.requireNonNull(log, "log");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
    }

    public Path configPath() {
        return dataDir.resolve(CONFIG_FILE_NAME);
    }

    public void ensureDefaultConfig() throws IOException {
        Files.createDirectories(dataDir);
        Path cfg = configPath();
        if (Files.exists(cfg)) {
            return;
        }

        String defaultYaml = """
                broadcast_ip: ipv4 address
                holding_server: waiting_lobby
                grace_sec: 90
                ping_every_sec: 2
                fallback_policy: offer   # strict | offer | auto
                servers:
                  vanilla1: \"mac_address\"
                groups:
                  default_group: [\"vanilla1\"]
                """;
        Files.writeString(cfg, defaultYaml, StandardCharsets.UTF_8);
        log.info("[WakeUpLobby] Created default configuration at {}", cfg);
    }

    public LobbyConfig load() throws IOException {
        Path cfg = configPath();
        try (Reader reader = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
            Map<String, Object> root = new Yaml().load(reader);
            if (root == null) {
                root = Map.of();
            }

            String broadcastIp = stringOrDefault(root.get("broadcast_ip"), "192.168.1.255");
            String holdingServer = stringOrDefault(root.get("holding_server"), "waiting_lobby");
            long graceSec = asLong(root.getOrDefault("grace_sec", 90));
            long pingEverySec = asLong(root.getOrDefault("ping_every_sec", 2));

            StickyRouter.FallbackPolicy fallbackPolicy = parseFallbackPolicy(
                    stringOrDefault(root.get("fallback_policy"), "offer"));

            Map<String, String> serverToMac = parseServerMacs(root.get("servers"));
            Map<String, List<String>> groups = parseGroups(root.get("groups"));
            Set<String> adminNames = parseAdmins(root.get("admins"));

            Map<String, String> perPortalSecrets = parsePerPortalSecrets(root.get("portal_secrets"));
            String globalSecret = stringOrDefault(root.get("portal_secret"), "").trim();

            return new LobbyConfig(
                    broadcastIp,
                    holdingServer,
                    graceSec,
                    pingEverySec,
                    fallbackPolicy,
                    serverToMac,
                    groups,
                    adminNames,
                    globalSecret,
                    perPortalSecrets
            );
        }
    }

    private static Map<String, String> parseServerMacs(Object value) {
        Map<String, String> result = new HashMap<>();
        Map<String, Object> map = asMap(value);
        if (map != null) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                result.put(entry.getKey(), Objects.toString(entry.getValue(), ""));
            }
        }
        return result;
    }

    private static Map<String, List<String>> parseGroups(Object value) {
        Map<String, List<String>> result = new HashMap<>();
        Map<String, Object> map = asMap(value);
        if (map != null) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                List<String> servers = new ArrayList<>();
                Object raw = entry.getValue();
                if (raw instanceof Iterable<?> iterable) {
                    for (Object element : iterable) {
                        servers.add(Objects.toString(element));
                    }
                }
                result.put(entry.getKey(), servers);
            }
        }
        return result;
    }

    private static Set<String> parseAdmins(Object value) {
        Set<String> result = new HashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                if (element != null) {
                    result.add(element.toString().toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private static Map<String, String> parsePerPortalSecrets(Object value) {
        Map<String, String> result = new HashMap<>();
        Map<String, Object> map = asMap(value);
        if (map != null) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                result.put(entry.getKey(), stringOrDefault(entry.getValue(), "").trim());
            }
        }
        return result;
    }

    private static StickyRouter.FallbackPolicy parseFallbackPolicy(String value) {
        try {
            return StickyRouter.FallbackPolicy.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return StickyRouter.FallbackPolicy.OFFER;
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(Objects.toString(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static String stringOrDefault(Object value, String defaultValue) {
        return Objects.toString(value, defaultValue);
    }
}
