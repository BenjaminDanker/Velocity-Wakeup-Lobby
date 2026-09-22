package com.silver.wakeup.authorization;

import com.silver.authorization.ServerId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;

/** Per-backend HMAC keys, separate from portal request signing material. */
public final class AuthorizationBackendKeys {
    private final Map<ServerId, byte[]> keys;

    private AuthorizationBackendKeys(Map<ServerId, byte[]> keys) {
        Map<ServerId, byte[]> copy = new HashMap<>();
        keys.forEach((server, key) -> copy.put(server, key.clone()));
        this.keys = Map.copyOf(copy);
    }

    public static AuthorizationBackendKeys load(Path dataDir, Set<ServerId> configured, Logger log) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("authorization-backend-keys.properties");
        if (Files.notExists(file)) {
            StringBuilder template = new StringBuilder("# Dedicated authorization HMAC keys; use a unique random 32+ byte key per server.\n")
                    .append("# Base64 values only. Restrict this file to the Velocity service account (0600).\n");
            configured.stream().sorted().forEach(server -> template.append(server.value()).append("=\n"));
            try {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException unsupported) {
                Files.createFile(file);
            }
            Files.writeString(file, template, StandardCharsets.UTF_8);
            log.warn("[Authorization] Created empty {}; provision a separate random HMAC key per backend", file.getFileName());
        }
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException unsupported) { /* Windows ACLs are managed by the host. */ }
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        Map<ServerId, byte[]> parsed = new HashMap<>();
        for (ServerId server : configured) {
            String encoded = properties.getProperty(server.value(), "").trim();
            if (encoded.isEmpty()) {
                log.warn("[Authorization] No dedicated HMAC key configured for backend {}", server);
                continue;
            }
            try {
                byte[] key = Base64.getDecoder().decode(encoded);
                if (key.length < 32) throw new IllegalArgumentException("key shorter than 32 bytes");
                parsed.put(server, key);
            } catch (IllegalArgumentException invalid) {
                log.error("[Authorization] Invalid dedicated HMAC key for backend {}; sync is disabled", server);
            }
        }
        Set<ServerId> duplicates = new HashSet<>();
        var entries = parsed.entrySet().stream().toList();
        for (int left = 0; left < entries.size(); left++) {
            for (int right = left + 1; right < entries.size(); right++) {
                if (Arrays.equals(entries.get(left).getValue(), entries.get(right).getValue())) {
                    duplicates.add(entries.get(left).getKey());
                    duplicates.add(entries.get(right).getKey());
                }
            }
        }
        duplicates.forEach(server -> log.error(
                "[Authorization] Duplicate backend key detected for {}; this backend's sync is disabled", server));
        duplicates.forEach(parsed::remove);
        return new AuthorizationBackendKeys(parsed);
    }

    public Optional<byte[]> key(ServerId server) {
        byte[] key = keys.get(server);
        return key == null ? Optional.empty() : Optional.of(key.clone());
    }
}
