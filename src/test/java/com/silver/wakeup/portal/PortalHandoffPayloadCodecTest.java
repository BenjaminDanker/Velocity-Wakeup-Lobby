package com.silver.wakeup.portal;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PortalHandoffPayloadCodecTest {

    @Test
    void decodeEmptyPayloadWhenFlagIsFalse() {
        byte[] payload = PortalHandoffPayloadCodec.encode(Optional.empty());

        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> decoded = PortalHandoffPayloadCodec.decode(payload);

        assertTrue(decoded.isEmpty(), "Expected decode to return empty optional when payload has no portal");
    }

    @Test
    void encodeAndDecodeRoundTrip() {
        UUID playerId = UUID.fromString("00000000-0000-4000-8000-000000000000");
        UUID transferId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String portalName = "nether_spawn";
        long expiresAt = 123456789L;

        byte[] payload = PortalHandoffPayloadCodec.encode(Optional.of(
                new PortalHandoffPayloadCodec.PortalHandoffResponse(transferId, playerId, portalName, expiresAt)));
        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> decoded = PortalHandoffPayloadCodec.decode(payload);

        assertTrue(decoded.isPresent(), "Expected decode to produce portal response");
        assertEquals(transferId, decoded.get().transferId());
        assertEquals(playerId, decoded.get().playerId());
        assertEquals(portalName, decoded.get().portalName());
        assertEquals(expiresAt, decoded.get().expiresAtMs());
    }

    @Test
    void decodeThrowsOnMalformedVarInt() {
        byte[] invalidPayload = new byte[] {2, 1, 0, 0};

        assertThrows(RuntimeException.class, () -> PortalHandoffPayloadCodec.decode(invalidPayload));
    }
}
