package com.silver.wakeup.plugin;

import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles security features for WakeUpLobby like rate limiting, greylisting, and whitelisting.
 * Validates connections before they are fully established to minimize overhead and prevent bot attacks.
 */
public class SecurityManager {

    private final Logger logger;
    private final WhitelistStore whitelistStore;

    // Rate limiting: ip -> [timestamp1, timestamp2, ...]
    private final Map<InetAddress, long[]> connectionHistory = new ConcurrentHashMap<>();
    
    // Greylisting: ip -> expiration timestamp
    private final Map<InetAddress, Long> greylist = new ConcurrentHashMap<>();

    // Settings
    private static final int MAX_CONN_PER_WINDOW = 3;
    private static final long WINDOW_MILLIS = 5000;
    private static final long GREYLIST_DURATION_MILLIS = 5 * 60 * 1000; // 5 minutes

    public SecurityManager(Logger logger, WhitelistStore whitelistStore) {
        this.logger = logger;
        this.whitelistStore = whitelistStore;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        InetSocketAddress remoteAddress = event.getConnection().getRemoteAddress();
        InetAddress ip = remoteAddress.getAddress();
        String username = event.getUsername();
        
        // 1. IP Greylist Check
        Long greylistExpiry = greylist.get(ip);
        if (greylistExpiry != null) {
            if (System.currentTimeMillis() < greylistExpiry) {
                logger.warn("[Security] Blocked connection from greylisted IP: {}", ip);
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    Component.text("Too many rapid connection attempts. Please try again in a few minutes.", NamedTextColor.RED)
                ));
                return;
            } else {
                // Greylist expired
                greylist.remove(ip);
            }
        }

        // 2. IP Rate Limiting Check
        if (isRateLimited(ip)) {
            logger.warn("[Security] IP {} hit connection rate limit, greylisting for 5m.", ip);
            greylist.put(ip, System.currentTimeMillis() + GREYLIST_DURATION_MILLIS);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                Component.text("Too many rapid connection attempts. Please try again later.", NamedTextColor.RED)
            ));
            return;
        }

        // 3. Username/Handshake Validation
        if (!isValidUsername(username)) {
            logger.warn("[Security] Invalid username from {}: {}", ip, username);
            greylist.put(ip, System.currentTimeMillis() + GREYLIST_DURATION_MILLIS);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                Component.text("Invalid username format or characters.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Hostname length validation isn't directly exposed in PreLoginEvent, 
        // but often handled by the proxy or can be checked via reflection/custom handlers.
        // We rely on standard Velocity hostname validation, but enforce strict UUID checks below.
        
        // Note: UUID Check cannot be perfectly done in PreLoginEvent for offline mode, 
        // but for online mode we assume Velocity handles the UUID auth.
        // However, we want to reject NON-WHITELISTED players.
        // Velocity handles the actual username->UUID resolution after PreLoginEvent.
        // We will do another check in ServerPreConnect or LoginEvent to enforce the whitelist strictly by UUID.
        // But if proxy is online mode and we get UUID here (if it's somehow available), we could check.
        // Usually, UUID is available in LoginEvent (after PreLogin).
        
        // See PlayerChooseInitialServerEvent or LoginEvent for whitelist enforcement.
    }
    
    @Subscribe
    public void onLogin(com.velocitypowered.api.event.connection.LoginEvent event) {
        // Here we have the resolved UUID for the player.
        UUID uuid = event.getPlayer().getUniqueId();
        
        // Add more rigorous forwarding validation checks if needed
        // Velocity handles 'online-mode' validation if configured properly, but we can verify player state
        if (!event.getPlayer().isOnlineMode()) {
            logger.warn("[Security] Rejecting offline mode connection for {}", event.getPlayer().getUsername());
            event.setResult(ResultedEvent.ComponentResult.denied(
                Component.text("Offline mode connections are not allowed.", NamedTextColor.RED)
            ));
            return;
        }
        
        // Validate public key if required by your setup (1.19+ feature usually handled by proxy)
        // If Velocity is properly configured, it already dropped players without keys if 'force-key-authentication' = true
        // But if you want to explicitly check:
        // if (event.getPlayer().getIdentifiedKey() == null) {
        //     logger.warn("[Security] Player {} is missing a public key.", event.getPlayer().getUsername());
        //     // Optional: reject
        // }

        if (!whitelistStore.isWhitelisted(uuid)) {
            logger.info("[Security] Connection from {} denied (Not Whitelisted).", uuid);
            event.setResult(ResultedEvent.ComponentResult.denied(
                Component.text("You are not whitelisted on this server.", NamedTextColor.RED)
            ));
        }
    }

    private boolean isRateLimited(InetAddress ip) {
        long now = System.currentTimeMillis();
        long[] history = connectionHistory.computeIfAbsent(ip, k -> new long[MAX_CONN_PER_WINDOW]);
        
        synchronized (history) {
            // Find oldest entry
            int oldestIdx = 0;
            long oldestTime = Long.MAX_VALUE;
            for (int i = 0; i < history.length; i++) {
                if (history[i] < oldestTime) {
                    oldestTime = history[i];
                    oldestIdx = i;
                }
            }

            // Check if oldest is within window
            if (now - oldestTime <= WINDOW_MILLIS) {
                return true; // Rate limited
            }

            // Replace oldest with current time
            history[oldestIdx] = now;
            return false;
        }
    }

    private boolean isValidUsername(String username) {
        if (username == null) return false;
        if (username.length() < 3 || username.length() > 16) return false;
        
        // Valid characters for Minecraft username
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            if (!(c >= 'a' && c <= 'z') &&
                !(c >= 'A' && c <= 'Z') &&
                !(c >= '0' && c <= '9') &&
                c != '_') {
                return false;
            }
        }
        return true;
    }
}