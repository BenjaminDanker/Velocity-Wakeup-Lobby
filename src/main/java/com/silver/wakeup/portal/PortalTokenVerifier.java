package com.silver.wakeup.portal;

import org.slf4j.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralizes the logic for validating per-server and global portal tokens.
 */
public class PortalTokenVerifier {
    private final Logger log;
    private volatile String globalSecret = "";
    private final Map<String, String> perServerSecrets = new ConcurrentHashMap<>();

    public PortalTokenVerifier(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Replace the current secret configuration.
     */
    public void updateSecrets(String globalSecret, Map<String, String> perServer) {
        this.globalSecret = normalize(globalSecret);
        perServerSecrets.clear();
        if (perServer != null) {
            for (Map.Entry<String, String> entry : perServer.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                perServerSecrets.put(key, normalize(entry.getValue()));
            }
        }
        log.info("[WakeUpLobby] Updated portal token configuration: globalSecret={}, perServer={} entries",
                describeSecret(this.globalSecret), perServerSecrets.size());
    }

    /**
     * Verify whether the supplied token is valid for the given target server.
     */
    public boolean verify(String target, String token) {
        Objects.requireNonNull(target, "target");
        token = normalize(token);

        log.info("[WakeUpLobby] verifyPortalToken: target={} tokenProvided={}", target, token.isEmpty() ? "<empty>" : "<present>");

        String perServerSecret = perServerSecrets.getOrDefault(target, "");
        if (!perServerSecret.isEmpty()) {
            boolean match = constantTimeEquals(perServerSecret, token);
            log.info("[WakeUpLobby] verifyPortalToken: per-server secret for '{}' = {} (match={})",
                    target, describeSecret(perServerSecret), match);
            return match;
        }

        if (!globalSecret.isEmpty()) {
            boolean match = constantTimeEquals(globalSecret, token);
            log.info("[WakeUpLobby] verifyPortalToken: global secret {} (match={})",
                    describeSecret(globalSecret), match);
            return match;
        }

        log.warn("[WakeUpLobby] verifyPortalToken: no secrets configured, verification failed");
        return false;
    }

    public Map<String, String> snapshotPerServerSecrets() {
        return Collections.unmodifiableMap(perServerSecrets);
    }

    public String snapshotGlobalSecret() {
        return globalSecret;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Compare secrets in constant time to avoid timing leaks.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected.length() != actual.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expected.length(); i++) {
            result |= expected.charAt(i) ^ actual.charAt(i);
        }
        return result == 0;
    }

    private static String describeSecret(String secret) {
        return secret.isEmpty() ? "(empty)" : "(set)";
    }
}
