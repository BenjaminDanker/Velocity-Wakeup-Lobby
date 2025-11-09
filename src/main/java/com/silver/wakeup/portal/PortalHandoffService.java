package com.silver.wakeup.portal;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks portal handoff state per player and produces handshake payloads.
 */
public class PortalHandoffService {
    private final ConcurrentHashMap<UUID, String> portalsByPlayer = new ConcurrentHashMap<>();
    private final Logger log;

    public PortalHandoffService(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public void rememberSourcePortal(UUID playerId, String portalName) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(portalName, "portalName");
        portalsByPlayer.put(playerId, portalName);
        log.info("[PortalHandoffService] stored source portal '{}' for {}", portalName, playerId);
    }

    public Optional<String> peekSourcePortal(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return Optional.ofNullable(portalsByPlayer.get(playerId));
    }

    public Optional<String> consumeSourcePortal(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        String portalName = portalsByPlayer.remove(playerId);
        if (portalName == null) {
            return Optional.empty();
        }
        log.info("[PortalHandoffService] consumed source portal '{}' for {}", portalName, playerId);
        return Optional.of(portalName);
    }

    public void clearSourcePortal(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        boolean removed = portalsByPlayer.remove(playerId) != null;
        log.info("[PortalHandoffService] cleared source portal for {} (removed={})", playerId, removed);
    }

    public byte[] createResponsePayload(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Optional<String> portalName = peekSourcePortal(playerId);
        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> response = portalName.map(name ->
            new PortalHandoffPayloadCodec.PortalHandoffResponse(playerId, name)
        );
        return PortalHandoffPayloadCodec.encode(response);
    }
}
