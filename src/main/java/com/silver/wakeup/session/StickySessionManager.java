package com.silver.wakeup.session;

import com.silver.wakeup.plugin.VelocityPlugin;
import com.silver.wakeup.portal.PortalHandoffService;
import com.silver.wakeup.wake.WakeService;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the async sticky waiting loop for servers: Wake-on-LAN, ping checks,
 * sticky state bookkeeping, and command execution once the destination is up.
 */
final class StickySessionManager {
    private final ProxyServer proxy;
    private final VelocityPlugin plugin;
    private final WakeService wakeService;
    private final PortalHandoffService portalHandoffService;
    private final Logger logger;
    private final String holdingServer;
    private final long graceMillis;
    private final long pingIntervalMillis;
    private final StickyRouter router;

    private record StickyState(String target, long deadlineMs, String originServer) {}

    private final ConcurrentHashMap<UUID, StickyState> stickyStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduledTask> tickTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentOkPing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastConnectAttemptMs = new ConcurrentHashMap<>();

    StickySessionManager(ProxyServer proxy,
                         VelocityPlugin plugin,
                         WakeService wakeService,
                         PortalHandoffService portalHandoffService,
                         Logger logger,
                         String holdingServer,
                         long graceSec,
                         long pingEverySec,
                         StickyRouter router) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.wakeService = wakeService;
        this.portalHandoffService = portalHandoffService;
        this.logger = logger;
        this.holdingServer = holdingServer;
        this.graceMillis = graceSec * 1000L;
        this.pingIntervalMillis = pingEverySec * 1000L;
        this.router = router;
    }

    void beginStickyWait(UUID playerId, String target, String originServer, String macAddress) {
        logger.info("[StickySession] beginStickyWait: player={} target={} origin={}", playerId, target, originServer);

        if (macAddress != null && !macAddress.isBlank()) {
            try {
                wakeService.wake(macAddress);
                logger.info("[StickySession] WoL packet sent for '{}'", target);
            } catch (Exception ex) {
                logger.warn("[StickySession] Failed to send WoL for '{}': {}", target, ex.toString());
            }
        }

        long deadline = System.currentTimeMillis() + graceMillis;
        stickyStates.put(playerId, new StickyState(target, deadline, originServer));
        logger.info("[StickySession] Stored sticky state for player={} deadline={}", playerId, deadline);

        tickTasks.computeIfAbsent(playerId, id -> proxy.getScheduler().buildTask(plugin, () -> tick(id))
                .delay(Duration.ofSeconds(1))
                .repeat(Duration.ofMillis(pingIntervalMillis))
                .schedule());
    }

    boolean hasStickyState(UUID playerId) {
        return stickyStates.containsKey(playerId);
    }

    void cleanupStickyState(UUID playerId) {
        stickyStates.remove(playerId);
        lastConnectAttemptMs.remove(playerId);
        ScheduledTask task = tickTasks.remove(playerId);
        if (task != null) {
            task.cancel();
        }
    }

    boolean isLikelyUp(String serverName) {
        Long t = recentOkPing.get(serverName);
        return t != null && (System.currentTimeMillis() - t) < 10_000;
    }

    private void tick(UUID playerId) {
        StickyState state = stickyStates.get(playerId);
        if (state == null) {
            logger.debug("[StickySession] tick: no state for player={} (already cleaned up)", playerId);
            cleanupStickyState(playerId);
            return;
        }

        Optional<Player> playerOpt = proxy.getPlayer(playerId);
        if (playerOpt.isEmpty()) {
            logger.warn("[StickySession] tick: player {} not found; cleaning up", playerId);
            cleanupStickyState(playerId);
            return;
        }

        Player player = playerOpt.get();

        String currentServer = player.getCurrentServer()
                .map(cs -> cs.getServerInfo().getName())
                .orElse(null);
        if (currentServer != null) {
            if (currentServer.equalsIgnoreCase(state.target())) {
                cleanupStickyState(playerId);
                return;
            }
            if (!currentServer.equalsIgnoreCase(holdingServer)) {
                logger.info("[StickySession] tick: player {} no longer in holding (current='{}'); cancelling sticky toward '{}'",
                        player.getUsername(), currentServer, state.target());
                cleanupStickyState(playerId);
                return;
            }
        }

        var serverOpt = proxy.getServer(state.target());
        if (serverOpt.isEmpty()) {
            player.sendMessage(Component.text("⚠ Unknown server " + state.target()));
            cleanupStickyState(playerId);
            return;
        }

        serverOpt.get().ping().whenComplete((pong, err) -> {
            if (!proxy.getPlayer(playerId).isPresent()) {
                cleanupStickyState(playerId);
                return;
            }

            if (err == null) {
                recentOkPing.put(state.target(), System.currentTimeMillis());
                long now = System.currentTimeMillis();
                Long lastAttempt = lastConnectAttemptMs.get(playerId);
                if (lastAttempt != null && (now - lastAttempt) < 5_000) {
                    return;
                }
                lastConnectAttemptMs.put(playerId, now);

                player.sendActionBar(Component.text("✅ " + state.target() + " is ready. Moving you now…"));
                router.markInternalOnce(playerId);

                Optional<String> sourcePortal = portalHandoffService.peekSourcePortal(playerId);
                player.createConnectionRequest(serverOpt.get()).connect().whenComplete((result, connectErr) -> {
                    if (result != null && result.isSuccessful()) {
                        sourcePortal.ifPresent(name -> {
                            player.spoofChatInput("/serverportals receive-portal " + name);
                            portalHandoffService.clearSourcePortal(playerId);
                        });
                        cleanupStickyState(playerId);
                        return;
                    }

                    if (connectErr != null) {
                        logger.warn("[StickySession] Connection to '{}' failed for {}: {}",
                                state.target(), player.getUsername(), connectErr.toString());
                    } else {
                        logger.warn("[StickySession] Connection to '{}' was not successful for {} (no exception)",
                                state.target(), player.getUsername());
                    }
                });
                return;
            }

            if (System.currentTimeMillis() >= state.deadlineMs()) {
                router.markReturnEligible(playerId, state.originServer(), state.target());

                String dest = plugin.computeReturnDestination(playerId);
                String lossList = plugin.formatLossList(plugin.computeReturnLossDisplayNames(playerId));
                if (dest == null || dest.isBlank()) {
                    player.sendMessage(Component.text(
                            "⚠ Server may be down. You can try reconnecting or use /return. Using /return will remove " + lossList + "."));
                } else {
                    player.sendMessage(Component.text(
                            "⚠ Server may be down. You can try reconnecting or use /return to go back to " + dest
                                    + ". Using /return will remove " + lossList + "."));
                }

                cleanupStickyState(playerId);
            } else {
                player.sendActionBar(Component.text("⏳ Starting " + state.target() + "…"));
            }
        });
    }
}
