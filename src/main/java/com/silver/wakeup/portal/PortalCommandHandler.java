package com.silver.wakeup.portal;

import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Encapsulates the behavioural logic for the /wl portal command so it can be unit tested.
 */
public class PortalCommandHandler {

    public interface Dependencies {
        boolean verifyPortalToken(String target, String token);
        CompletionStage<?> createTransfer(UUID playerId, String sourceServer, String targetServer, String arrivalPortal);
        Optional<String> currentServerName(Player player);
        Optional<HoldingConnection> resolveHoldingServer(Player player, String target, Optional<String> originServer);
        void beginStickyWait(UUID playerId, String target, Optional<String> originServer);
        void markInternalOnce(UUID playerId, String target);
        void notifyInvalidToken(Player player);
        void notifyTransferFailure(Player player);
        String holdingServerName();

        /**
         * Sends the player straight to the target when it is already up (no stop in the waiting lobby). Completes with
         * true when they were moved; false means use the lobby and wait as usual.
         */
        default CompletionStage<Boolean> tryDirectConnect(Player player, String target) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
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

    public boolean handle(Player player, String targetServer, String token, Optional<String> arrivalPortalOpt) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetServer, "targetServer");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(arrivalPortalOpt, "arrivalPortalOpt");

        log.info("[WakeUpLobby] /wl portal: player={} target={} arrivalPortal={}",
                player.getUsername(), targetServer, arrivalPortalOpt.orElse("<none>"));

        if (!dependencies.verifyPortalToken(targetServer, token)) {
            log.warn("[WakeUpLobby] Portal token verification failed for target={}", targetServer);
            dependencies.notifyInvalidToken(player);
            return false;
        }

        log.info("[WakeUpLobby] Portal token verified successfully");

        return handleAuthorized(player, targetServer, arrivalPortalOpt);
    }

    public boolean handleAuthorized(Player player, String targetServer, Optional<String> arrivalPortalOpt) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetServer, "targetServer");
        Objects.requireNonNull(arrivalPortalOpt, "arrivalPortalOpt");

        UUID playerId = player.getUniqueId();
        Optional<String> originServer = dependencies.currentServerName(player);
        log.info("[WakeUpLobby] /wl portal: origin='{}' target='{}' holding='{}'",
                originServer.orElse("<none>"), targetServer, dependencies.holdingServerName());

        Optional<HoldingConnection> holdingConnection = dependencies.resolveHoldingServer(player, targetServer, originServer);
        if (holdingConnection.isEmpty()) {
            log.error("[WakeUpLobby] Holding server '{}' not found when processing /wl portal for player {}",
                    dependencies.holdingServerName(), player.getUsername());
            return false;
        }

        String sourceServer = originServer.orElse("unknown");
        String arrivalPortal = arrivalPortalOpt.filter(value -> !value.isBlank()).orElse("");
        dependencies.createTransfer(playerId, sourceServer, targetServer, arrivalPortal)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        log.error("[WakeUpLobby] Failed to persist portal transfer for {} -> {}",
                                player.getUsername(), targetServer, failure);
                        dependencies.notifyTransferFailure(player);
                        return;
                    }
                    dependencies.tryDirectConnect(player, targetServer).whenComplete((direct, directFailure) -> {
                        if (directFailure == null && Boolean.TRUE.equals(direct)) {
                            log.info("[WakeUpLobby] {} went straight to '{}' (it was up)", player.getUsername(), targetServer);
                            return;
                        }
                        dependencies.beginStickyWait(playerId, targetServer, originServer);
                        dependencies.markInternalOnce(playerId, dependencies.holdingServerName());
                        holdingConnection.get().connect();
                    });
                });
        return true;
    }
}
