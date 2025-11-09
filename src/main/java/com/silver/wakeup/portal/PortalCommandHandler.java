package com.silver.wakeup.portal;

import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Encapsulates the behavioural logic for the /wl portal command so it can be unit tested.
 */
public class PortalCommandHandler {

    public interface Dependencies {
        boolean verifyPortalToken(String target, String token);
        void rememberSourcePortal(UUID playerId, String portalName);
        void unlockServerFor(UUID playerId, String target);
        Optional<String> currentServerName(Player player);
        Optional<HoldingConnection> resolveHoldingServer(Player player, String target, Optional<String> originServer);
        void beginStickyWait(UUID playerId, String target, Optional<String> originServer);
        void markInternalOnce(UUID playerId);
        void notifyInvalidToken(Player player);
        String holdingServerName();
    }

    @FunctionalInterface
    public interface HoldingConnection {
        void connect();
    }

    private final Logger log;
    private final Dependencies dependencies;

    public PortalCommandHandler(Logger log, Dependencies dependencies) {
        this.log = Objects.requireNonNull(log, "log");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    public boolean handle(Player player, String targetServer, String token, Optional<String> sourcePortalOpt) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetServer, "targetServer");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(sourcePortalOpt, "sourcePortalOpt");

        log.info("[WakeUpLobby] /wl portal: player={} target={} token={} sourcePortal={}",
                player.getUsername(), targetServer, token, sourcePortalOpt.orElse("<none>"));

        if (!dependencies.verifyPortalToken(targetServer, token)) {
            log.warn("[WakeUpLobby] Portal token verification failed for target={} token={}", targetServer, token);
            dependencies.notifyInvalidToken(player);
            return false;
        }

        log.info("[WakeUpLobby] Portal token verified successfully");

        UUID playerId = player.getUniqueId();
        sourcePortalOpt.ifPresentOrElse(
                portal -> {
                    dependencies.rememberSourcePortal(playerId, portal);
                    log.info("[WakeUpLobby] Stored source portal '{}' for player {}", portal, player.getUsername());
                },
                () -> log.warn("[WakeUpLobby] No source portal name provided for player {}", player.getUsername())
        );

        dependencies.unlockServerFor(playerId, targetServer);
        Optional<String> originServer = dependencies.currentServerName(player);
        log.info("[WakeUpLobby] /wl portal: origin='{}' target='{}' holding='{}'",
                originServer.orElse("<none>"), targetServer, dependencies.holdingServerName());

        Optional<HoldingConnection> holdingConnection = dependencies.resolveHoldingServer(player, targetServer, originServer);
        if (holdingConnection.isEmpty()) {
            log.error("[WakeUpLobby] Holding server '{}' not found when processing /wl portal for player {}",
                    dependencies.holdingServerName(), player.getUsername());
            return false;
        }

        dependencies.beginStickyWait(playerId, targetServer, originServer);
        dependencies.markInternalOnce(playerId);
        holdingConnection.get().connect();
        return true;
    }
}
