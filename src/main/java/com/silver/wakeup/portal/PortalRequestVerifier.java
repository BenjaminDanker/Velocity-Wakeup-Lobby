package com.silver.wakeup.portal;

import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.silver.wakeup.portal.PortalRequestPayloadCodec.PortalRequest;

/**
 * Verifies backend->proxy portal requests.
 */
public final class PortalRequestVerifier {
    private static final long DEFAULT_TTL_MS = 5_000L;

    private final Logger log;
    private final Map<String, String> backendSecrets = new ConcurrentHashMap<>();

    // replay protection: backend|nonce -> expiryMs
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    private volatile long ttlMs = DEFAULT_TTL_MS;

    public PortalRequestVerifier(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public void updateSecrets(Map<String, String> backendSecrets) {
        this.backendSecrets.clear();
        if (backendSecrets != null) {
            for (var entry : backendSecrets.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = entry.getKey().trim();
                if (key.isEmpty()) {
                    continue;
                }
                this.backendSecrets.put(key, normalize(entry.getValue()));
            }
        }
        log.info("[WakeUpLobby] Updated backend portal request secrets: {} entries", this.backendSecrets.size());
    }

    public Map<String, String> snapshotSecrets() {
        return Collections.unmodifiableMap(backendSecrets);
    }

    public boolean verify(String backendServerName, PortalRequest request) {
        Objects.requireNonNull(backendServerName, "backendServerName");
        Objects.requireNonNull(request, "request");

        String secret = Optional.ofNullable(backendSecrets.get(backendServerName))
            .filter(s -> !s.isBlank())
            .orElseGet(() -> Optional.ofNullable(backendSecrets.get("*"))
                .orElse(""));
        if (secret.isEmpty()) {
            log.warn("[WakeUpLobby] Portal request rejected: no secret configured for backend '{}'", backendServerName);
            return false;
        }

        long now = System.currentTimeMillis();
        long age = Math.abs(now - request.issuedAtMs());
        if (age > ttlMs) {
            log.warn("[WakeUpLobby] Portal request rejected: expired/too far clock skew backend='{}' ageMs={} ttlMs={}",
                    backendServerName, age, ttlMs);
            return false;
        }

        pruneNonces(now);
        String nonceKey = backendServerName + "|" + request.nonce();
        Long existing = seenNonces.putIfAbsent(nonceKey, request.issuedAtMs() + ttlMs);
        if (existing != null) {
            log.warn("[WakeUpLobby] Portal request rejected: replayed nonce backend='{}' nonce='{}'", backendServerName, request.nonce());
            return false;
        }

        byte[] expected = hmacSha256(secret, PortalRequestPayloadCodec.encodeUnsigned(request.unsigned()));
        boolean match = constantTimeEquals(expected, request.signature());
        if (!match) {
            log.warn("[WakeUpLobby] Portal request rejected: bad signature backend='{}' target='{}'", backendServerName, request.targetServer());
            return false;
        }

        return true;
    }

    private void pruneNonces(long nowMs) {
        if (seenNonces.isEmpty()) {
            return;
        }
        seenNonces.entrySet().removeIf(e -> e.getValue() == null || e.getValue() < nowMs);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static byte[] hmacSha256(String secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to compute HMAC", ex);
        }
    }

    private static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.length != actual.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expected.length; i++) {
            result |= expected[i] ^ actual[i];
        }
        return result == 0;
    }
}
