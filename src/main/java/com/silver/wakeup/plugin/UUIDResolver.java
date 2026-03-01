package com.silver.wakeup.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Resolves Minecraft player names to UUIDs.
 * First checks online players on the proxy, then falls back to Mojang API with retry logic.
 */
public class UUIDResolver {
    private final ProxyServer proxy;
    private final Logger logger;
    
    private static final int MAX_RETRIES = 3;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final long OVERALL_TIMEOUT_MS = 10000; // Max 10s total
    private static final int INITIAL_RETRY_DELAY_MS = 500;

    public UUIDResolver(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /**
     * Resolves a player name to a UUID.
     * @param name The player name to resolve
     * @return CompletableFuture containing the UUID if found, empty otherwise
     */
    public CompletableFuture<Optional<UUID>> resolveUUID(String name) {
        // First check if player is online
        Optional<UUID> onlineUuid = proxy.getPlayer(name).map(player -> player.getUniqueId());
        if (onlineUuid.isPresent()) {
            return CompletableFuture.completedFuture(onlineUuid);
        }

        // Fall back to Mojang API with retry logic and timeout
        CompletableFuture<Optional<UUID>> future = CompletableFuture.supplyAsync(() -> 
            resolveUUIDWithRetry(name)
        );
        
        // Apply overall timeout to prevent indefinite waiting
        return future.orTimeout(OVERALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    logger.warn("[WakeUpLobby] UUID resolution for '{}' timed out or failed: {}", name, ex.getMessage());
                    return Optional.empty();
                });
    }
    
    private Optional<UUID> resolveUUIDWithRetry(String name) {
        Exception lastException = null;
        int retryDelay = INITIAL_RETRY_DELAY_MS;
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String urlString = "https://api.mojang.com/users/profiles/minecraft/" + name;
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }

                        JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                        String uuidStr = json.get("id").getAsString();
                        String formattedUuid = formatUUID(uuidStr);
                        if (attempt > 1) {
                            logger.info("[WakeUpLobby] Successfully resolved '{}' on attempt {}", name, attempt);
                        }
                        return Optional.of(UUID.fromString(formattedUuid));
                    }
                } else if (responseCode == 204 || responseCode == 404) {
                    // Player doesn't exist - no need to retry
                    logger.debug("[WakeUpLobby] Player '{}' not found via Mojang API", name);
                    return Optional.empty();
                } else if (responseCode == 429) {
                    // Rate limited - definitely retry
                    logger.warn("[WakeUpLobby] Mojang API rate limited (attempt {}/{})", attempt, MAX_RETRIES);
                    lastException = new Exception("Rate limited (429)");
                } else {
                    logger.warn("[WakeUpLobby] Mojang API returned code {} (attempt {}/{})", responseCode, attempt, MAX_RETRIES);
                    lastException = new Exception("HTTP " + responseCode);
                }
            } catch (Exception e) {
                logger.warn("[WakeUpLobby] Failed to resolve UUID for '{}' (attempt {}/{}): {}", name, attempt, MAX_RETRIES, e.getMessage());
                lastException = e;
            }
            
            // Sleep before retry (except on last attempt)
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(retryDelay);
                    retryDelay *= 2; // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.warn("[WakeUpLobby] All {} attempts failed for resolving '{}'", MAX_RETRIES, name);
        return Optional.empty();
    }

    /**
     * Formats a UUID string without dashes to standard UUID format.
     * @param uuidStr UUID string without dashes (32 hex chars)
     * @return Formatted UUID string with dashes
     */
    private String formatUUID(String uuidStr) {
        if (uuidStr.length() != 32) {
            throw new IllegalArgumentException("Invalid UUID string length: " + uuidStr.length());
        }
        return String.format("%s-%s-%s-%s-%s",
                uuidStr.substring(0, 8),
                uuidStr.substring(8, 12),
                uuidStr.substring(12, 16),
                uuidStr.substring(16, 20),
                uuidStr.substring(20, 32));
    }

    /**
     * Attempts to resolve a player name from a UUID.
     * Checks online players first, then Mojang API.
     * @param uuid The UUID to resolve
     * @return CompletableFuture containing the name if found, empty otherwise
     */
    public CompletableFuture<Optional<String>> resolveName(UUID uuid) {
        // Check if player is online
        Optional<String> onlineName = proxy.getPlayer(uuid).map(player -> player.getUsername());
        if (onlineName.isPresent()) {
            return CompletableFuture.completedFuture(onlineName);
        }

        // Fall back to Mojang API with retry logic and timeout
        CompletableFuture<Optional<String>> future = CompletableFuture.supplyAsync(() -> 
            resolveNameWithRetry(uuid)
        );
        
        // Apply overall timeout to prevent indefinite waiting
        return future.orTimeout(OVERALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    logger.debug("[WakeUpLobby] Name resolution for '{}' timed out or failed: {}", uuid, ex.getMessage());
                    return Optional.empty();
                });
    }
    
    private Optional<String> resolveNameWithRetry(UUID uuid) {
        Exception lastException = null;
        int retryDelay = INITIAL_RETRY_DELAY_MS;
        
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String uuidStr = uuid.toString().replace("-", "");
                String urlString = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuidStr;
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }

                        JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                        String name = json.get("name").getAsString();
                        if (attempt > 1) {
                            logger.info("[WakeUpLobby] Successfully resolved name for '{}' on attempt {}", uuid, attempt);
                        }
                        return Optional.of(name);
                    }
                } else if (responseCode == 204 || responseCode == 404) {
                    // UUID doesn't exist - no need to retry
                    logger.debug("[WakeUpLobby] UUID '{}' not found via Mojang API", uuid);
                    return Optional.empty();
                } else if (responseCode == 429) {
                    // Rate limited - definitely retry
                    logger.warn("[WakeUpLobby] Mojang API rate limited (attempt {}/{})", attempt, MAX_RETRIES);
                    lastException = new Exception("Rate limited (429)");
                } else {
                    logger.warn("[WakeUpLobby] Mojang API returned code {} (attempt {}/{})", responseCode, attempt, MAX_RETRIES);
                    lastException = new Exception("HTTP " + responseCode);
                }
            } catch (Exception e) {
                logger.debug("[WakeUpLobby] Failed to resolve name for '{}' (attempt {}/{}): {}", uuid, attempt, MAX_RETRIES, e.getMessage());
                lastException = e;
            }
            
            // Sleep before retry (except on last attempt)
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(retryDelay);
                    retryDelay *= 2; // Exponential backoff
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        logger.debug("[WakeUpLobby] All {} attempts failed for resolving name for '{}'", MAX_RETRIES, uuid);
        return Optional.empty();
    }
}
