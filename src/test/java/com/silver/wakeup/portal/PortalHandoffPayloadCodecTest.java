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
        String portalName = "nether_spawn";

        byte[] payload = PortalHandoffPayloadCodec.encode(Optional.of(new PortalHandoffPayloadCodec.PortalHandoffResponse(playerId, portalName)));
        Optional<PortalHandoffPayloadCodec.PortalHandoffResponse> decoded = PortalHandoffPayloadCodec.decode(payload);

        assertTrue(decoded.isPresent(), "Expected decode to produce portal response");
        assertEquals(playerId, decoded.get().playerId());
        assertEquals(portalName, decoded.get().portalName());
    }

    @Test
    void decodeThrowsOnMalformedVarInt() {
        byte[] invalidPayload = new byte[] {1, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0};

        assertThrows(IllegalArgumentException.class, () -> PortalHandoffPayloadCodec.decode(invalidPayload));
    }
}
