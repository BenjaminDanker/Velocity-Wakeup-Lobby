package com.silver.wakeup.portal;

import com.silver.wakeup.state.PortalTransfer;
import com.silver.wakeup.state.RoutingStateService;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortalHandoffServiceTest {
    private final RoutingStateService routing = mock(RoutingStateService.class);
    private final PortalHandoffService service = new PortalHandoffService(routing, NOPLogger.NOP_LOGGER);

    @Test
    void createsEmptyPayloadWhenNoClaimIsAvailable() {
        UUID playerId = UUID.randomUUID();
        when(routing.consumeHandoff(playerId, "sky-island")).thenReturn(Optional.empty());

        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> decoded =
                PortalHandoffPayloadCodec.decode(service.consumeResponsePayload(playerId, "sky-island"));

        assertTrue(decoded.isEmpty());
        verify(routing).consumeHandoff(playerId, "sky-island");
    }

    @Test
    void consumesAndEncodesExactClaimOnce() {
        UUID playerId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(30);
        PortalTransfer transfer = new PortalTransfer(transferId, playerId, "vanilla1", "sky-island",
                "sky-island_entrance", PortalTransfer.Status.CLAIMED, Instant.now(), expiry);
        when(routing.consumeHandoff(playerId, "sky-island")).thenReturn(Optional.of(transfer), Optional.empty());

        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> first =
                PortalHandoffPayloadCodec.decode(service.consumeResponsePayload(playerId, "sky-island"));
        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> second =
                PortalHandoffPayloadCodec.decode(service.consumeResponsePayload(playerId, "sky-island"));

        assertTrue(first.isPresent());
        assertEquals(transferId, first.orElseThrow().transferId());
        assertEquals("sky-island_entrance", first.orElseThrow().portalName());
        assertTrue(second.isEmpty(), "a claimed arrival must not be replayed");
    }
}
