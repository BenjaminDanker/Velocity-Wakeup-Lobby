package com.silver.wakeup.config;

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

# Portal token configuration for `/wl portal <target> <token> [source_portal]`.
# - If `portal_secrets.<target>` is set, it MUST match for that target.
# - Otherwise, `portal_secret` (global) is used.
# - If neither are set, every `/wl portal` call will fail with "Invalid portal token".
portal_secret: ""
portal_secrets:
        cave: ""

# Backend -> proxy portal request authentication.
# Each backend server that can initiate a portal transfer MUST have a shared secret here.
# Key: the Velocity server name (e.g. "vanilla1"). Value: a long random string.
# You can also use a wildcard entry "*" to apply the same secret to all backends.
backend_portal_request_secrets:
    "*": ""
servers:
    vanilla1: "mac_address"
groups:
    default_group: ["vanilla1"]
# Servers players can return to after grace expires (in order)
return_servers: ["vanilla1", "magic"]
# Specials removed when using /return (by origin server)
return_specials:
    ocean:
        - key: id
          value: special_drop
          display: Special Heart of the Sea
        - key: id
          value: special_sea_lantern
          display: Special Sea Lantern
    sky-island:
        - key: id
          value: special_arrow
          display: Special Arrow
        - key: id
          value: special_feather
          display: Special Feather
    desert:
        - key: id
          value: special_dead_bush
          display: Special Dead Bush
    cave:
        - key: id
          value: special_coal_ore
          display: Special Coal Ore
""";
        Files.writeString(cfg, defaultYaml, StandardCharsets.UTF_8);
        log.info("[WakeUpLobby] Created default configuration at {}", cfg);
    }

    public LobbyConfig load() throws IOException {
        Path cfg = configPath();
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        }

        if (root == null) {
            root = Map.of();
        }

        String broadcastIp = stringOrDefault(root.get("broadcast_ip"), "192.168.1.255");
        String holdingServer = stringOrDefault(root.get("holding_server"), "waiting_lobby");
        long graceSec = asLong(root.getOrDefault("grace_sec", 90));
        long pingEverySec = asLong(root.getOrDefault("ping_every_sec", 2));

        Map<String, String> serverToMac = parseServerMacs(root.get("servers"));
        Map<String, List<String>> groups = parseGroups(root.get("groups"));
        Set<String> adminNames = parseAdmins(root.get("admins"));

        Map<String, String> perPortalSecrets = parsePerPortalSecrets(root.get("portal_secrets"));
        String globalSecret = stringOrDefault(root.get("portal_secret"), "").trim();

        Map<String, String> backendPortalRequestSecrets = parsePerPortalSecrets(root.get("backend_portal_request_secrets"));

        List<String> returnServers = parseStringList(root.get("return_servers"), List.of("vanilla1", "magic"));
        Map<String, List<ReturnSpecial>> returnSpecials = parseReturnSpecials(root.get("return_specials"));

        return new LobbyConfig(
                broadcastIp,
                holdingServer,
                graceSec,
                pingEverySec,
                serverToMac,
                groups,
                adminNames,
                globalSecret,
                perPortalSecrets,
            backendPortalRequestSecrets,
                returnServers,
                returnSpecials
        );
    }

    private static List<String> parseStringList(Object value, List<String> defaultValue) {
        if (value instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object element : iterable) {
                if (element == null) {
                    continue;
                }
                String s = element.toString().trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out.isEmpty() ? defaultValue : out;
        }
        return defaultValue;
    }

    private static Map<String, List<ReturnSpecial>> parseReturnSpecials(Object value) {
        Map<String, List<ReturnSpecial>> result = new HashMap<>();
        Map<String, Object> map = asMap(value);
        if (map == null) {
            return result;
        }

        for (var entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String origin = entry.getKey().trim();
            if (origin.isEmpty()) {
                continue;
            }

            List<ReturnSpecial> specials = new ArrayList<>();
            Object rawList = entry.getValue();
            if (rawList instanceof Iterable<?> iterable) {
                for (Object element : iterable) {
                    Map<String, Object> spec = asMap(element);
                    if (spec == null) {
                        continue;
                    }
                    String key = stringOrDefault(spec.get("key"), "").trim();
                    String val = stringOrDefault(spec.get("value"), "").trim();
                    String display = stringOrDefault(spec.get("display"), "").trim();
                    if (!key.isEmpty() && !val.isEmpty() && !display.isEmpty()) {
                        specials.add(new ReturnSpecial(key, val, display));
                    }
                }
            }

            if (!specials.isEmpty()) {
                result.put(origin, specials);
            }
        }
        return result;
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
