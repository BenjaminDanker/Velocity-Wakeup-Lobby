package com.silver.wakeup.portal;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalHandoffServiceTest {

    private final PortalHandoffService service = new PortalHandoffService(NOPLogger.NOP_LOGGER);

    @Test
    void createsEmptyPayloadWhenNoPortalRemembered() {
        UUID playerId = UUID.randomUUID();

        byte[] payload = service.createResponsePayload(playerId);
        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> decoded = PortalHandoffPayloadCodec.decode(payload);

        assertTrue(decoded.isEmpty(), "Expect handshake payload to be empty when no portal is stored");
    }

    @Test
    void createResponsePayloadIncludesPortalData() {
        UUID playerId = UUID.randomUUID();
        String portalName = "spawn_hub";
        service.rememberSourcePortal(playerId, portalName);

        byte[] payload = service.createResponsePayload(playerId);
        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> decoded = PortalHandoffPayloadCodec.decode(payload);

        assertTrue(decoded.isPresent(), "Expect payload to include portal data");
        assertEquals(playerId, decoded.get().playerId());
        assertEquals(portalName, decoded.get().portalName());
        assertEquals(Optional.of(portalName), service.peekSourcePortal(playerId), "createResponsePayload should not consume portal state");
    }

    @Test
    void clearSourcePortalRemovesStoredValue() {
        UUID playerId = UUID.randomUUID();
        service.rememberSourcePortal(playerId, "spawn_hub");

        service.clearSourcePortal(playerId);

        assertTrue(service.peekSourcePortal(playerId).isEmpty(), "Expected portal data to be cleared");
    }

    @Test
    void consumeSourcePortalReturnsValueOnce() {
        UUID playerId = UUID.randomUUID();
        service.rememberSourcePortal(playerId, "spawn_hub");

        Optional<String> first = service.consumeSourcePortal(playerId);
        Optional<String> second = service.consumeSourcePortal(playerId);

        assertEquals(Optional.of("spawn_hub"), first, "consumeSourcePortal should return stored portal once");
        assertTrue(second.isEmpty(), "consumeSourcePortal should clear stored value");
    }

    @Test
    void remembersPortalsPerPlayerIndependently() {
        UUID playerOne = UUID.randomUUID();
        UUID playerTwo = UUID.randomUUID();

        service.rememberSourcePortal(playerOne, "lobby_a");
        service.rememberSourcePortal(playerTwo, "lobby_b");

        assertEquals(Optional.of("lobby_a"), service.peekSourcePortal(playerOne));
        assertEquals(Optional.of("lobby_b"), service.peekSourcePortal(playerTwo));

        service.clearSourcePortal(playerOne);

        assertTrue(service.peekSourcePortal(playerOne).isEmpty(), "First player's portal should be cleared");
        assertEquals(Optional.of("lobby_b"), service.peekSourcePortal(playerTwo), "Second player's portal should remain intact");
    }
}
