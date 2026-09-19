package com.silver.wakeup.portal;

import com.silver.wakeup.state.PortalTransfer;
import com.silver.wakeup.state.RoutingStateService;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Coordinates durable, target-bound and single-consumption portal handoffs. */
public final class PortalHandoffService {
    private final RoutingStateService routingState;
    private final Logger log;

    public PortalHandoffService(RoutingStateService routingState, Logger log) {
        this.routingState = Objects.requireNonNull(routingState, "routingState");
        this.log = Objects.requireNonNull(log, "log");
    }

    public CompletableFuture<PortalTransfer> createTransfer(
            UUID playerId, String sourceServer, String targetServer, String arrivalPortal) {
        return routingState.createTransfer(playerId, sourceServer, targetServer, arrivalPortal);
    }

    public Optional<PortalTransfer> pendingTransfer(UUID playerId) {
        return routingState.pendingTransfer(playerId);
    }

    public CompletableFuture<Optional<PortalTransfer>> claimForTarget(UUID playerId, String targetServer) {
        return routingState.claimForTarget(playerId, targetServer);
    }

    public CompletableFuture<Void> connectionFailed(UUID playerId, PortalTransfer transfer) {
        return routingState.connectionFailed(playerId, transfer);
    }

    public CompletableFuture<Void> completeConnectedTransfer(UUID playerId, String server) {
        return routingState.completeConnectedTransfer(playerId, server);
    }

    public CompletableFuture<Void> cancelActiveTransfer(UUID playerId) {
        return routingState.cancelActiveTransfer(playerId);
    }

    public byte[] consumeResponsePayload(UUID playerId, String requestingServer) {
        Optional<PortalTransfer> transfer = routingState.consumeHandoff(playerId, requestingServer)
                .filter(value -> !value.arrivalPortal().isBlank());
        transfer.ifPresent(value -> log.info(
                "[PortalHandoffService] delivering transfer={} arrival='{}' player={} target={}",
                value.transferId(), value.arrivalPortal(), playerId, value.targetServer()));
        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> response = transfer.map(value ->
                new PortalHandoffPayloadCodec.PortalHandoffResponse(
                        value.transferId(), playerId, value.arrivalPortal(), value.expiresAt().toEpochMilli()));
        return PortalHandoffPayloadCodec.encode(response);
    }
}
