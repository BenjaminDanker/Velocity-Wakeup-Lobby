package com.silver.wakeup.state;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PortalTransfer(
        UUID transferId,
        UUID playerId,
        String sourceServer,
        String targetServer,
        String arrivalPortal,
        Status status,
        Instant issuedAt,
        Instant expiresAt
) {
    public enum Status { PENDING, CLAIMED, COMPLETED, CANCELLED, EXPIRED }

    public PortalTransfer {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sourceServer, "sourceServer");
        Objects.requireNonNull(targetServer, "targetServer");
        arrivalPortal = arrivalPortal == null ? "" : arrivalPortal;
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public PortalTransfer withStatus(Status newStatus) {
        return new PortalTransfer(transferId, playerId, sourceServer, targetServer,
                arrivalPortal, newStatus, issuedAt, expiresAt);
    }

    public boolean expiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
