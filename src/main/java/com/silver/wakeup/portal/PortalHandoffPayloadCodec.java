package com.silver.wakeup.portal;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Encodes and decodes the Velocity ↔ Fabric portal handoff payload.
 */
public final class PortalHandoffPayloadCodec {
    private PortalHandoffPayloadCodec() {
    }

    public static byte[] encode(Optional<PortalHandoffResponse> response) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        if (response.isPresent()) {
            PortalHandoffResponse payload = response.get();
            out.writeBoolean(true);
            writeString(out, payload.portalName());
            out.writeLong(payload.playerId().getMostSignificantBits());
            out.writeLong(payload.playerId().getLeastSignificantBits());
        } else {
            out.writeBoolean(false);
        }
        return out.toByteArray();
    }

    public static Optional<PortalHandoffResponse> decode(byte[] payload) {
        ByteArrayDataInput in = ByteStreams.newDataInput(payload);
        boolean hasPortal = in.readBoolean();
        if (!hasPortal) {
            return Optional.empty();
        }

        String portalName = readString(in);
        long msb = in.readLong();
        long lsb = in.readLong();
        return Optional.of(new PortalHandoffResponse(new UUID(msb, lsb), portalName));
    }

    private static void writeString(ByteArrayDataOutput out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(ByteArrayDataInput in) {
        int length = readVarInt(in);
        if (length < 0) {
            throw new IllegalArgumentException("Negative string length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(ByteArrayDataOutput out, int value) {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(ByteArrayDataInput in) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0x7F);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) {
                throw new IllegalArgumentException("VarInt is too big");
            }
        } while ((read & 0x80) != 0);

        return result;
    }

    public record PortalHandoffResponse(UUID playerId, String portalName) {
        public PortalHandoffResponse {
            if (portalName == null || portalName.isBlank()) {
                throw new IllegalArgumentException("portalName must not be blank");
            }
        }
    }
}
