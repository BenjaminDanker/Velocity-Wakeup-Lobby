package com.silver.wakeup.portal;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Codec for backend -> proxy portal requests.
 *
 * Format (version 1):
 * - byte version
 * - long issuedAtMs
 * - UUID (msb, lsb)
 * - targetServer (string)
 * - sourcePortal (string, may be empty)
 * - nonce (string)
 * - signature bytes (varint length + bytes)
 */
public final class PortalRequestPayloadCodec {
    public static final byte VERSION_1 = 1;

    private PortalRequestPayloadCodec() {
    }

    public static byte[] encode(PortalRequest request) {
        Objects.requireNonNull(request, "request");

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(VERSION_1);
        out.writeLong(request.issuedAtMs());
        out.writeLong(request.playerId().getMostSignificantBits());
        out.writeLong(request.playerId().getLeastSignificantBits());
        writeString(out, request.targetServer());
        writeString(out, request.sourcePortal());
        writeString(out, request.nonce());
        writeBytes(out, request.signature());
        return out.toByteArray();
    }

    public static Optional<PortalRequest> decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }

        ByteArrayDataInput in = ByteStreams.newDataInput(payload);
        byte version = in.readByte();
        if (version != VERSION_1) {
            return Optional.empty();
        }

        long issuedAtMs = in.readLong();
        long msb = in.readLong();
        long lsb = in.readLong();
        UUID playerId = new UUID(msb, lsb);

        String target = readString(in);
        String sourcePortal = readString(in);
        String nonce = readString(in);
        byte[] signature = readBytes(in, 128);

        if (target.isBlank() || nonce.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new PortalRequest(playerId, target, sourcePortal, issuedAtMs, nonce, signature));
    }

    public static byte[] encodeUnsigned(PortalRequestUnsigned unsignedRequest) {
        Objects.requireNonNull(unsignedRequest, "unsignedRequest");

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(VERSION_1);
        out.writeLong(unsignedRequest.issuedAtMs());
        out.writeLong(unsignedRequest.playerId().getMostSignificantBits());
        out.writeLong(unsignedRequest.playerId().getLeastSignificantBits());
        writeString(out, unsignedRequest.targetServer());
        writeString(out, unsignedRequest.sourcePortal());
        writeString(out, unsignedRequest.nonce());
        return out.toByteArray();
    }

    public static String generateNonce() {
        byte[] bytes = new byte[18];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void writeString(ByteArrayDataOutput out, String value) {
        String safe = value == null ? "" : value;
        byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
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

    private static void writeBytes(ByteArrayDataOutput out, byte[] bytes) {
        byte[] safe = bytes == null ? new byte[0] : bytes;
        writeVarInt(out, safe.length);
        out.write(safe);
    }

    private static byte[] readBytes(ByteArrayDataInput in, int maxLen) {
        int length = readVarInt(in);
        if (length < 0 || length > maxLen) {
            throw new IllegalArgumentException("Bad bytes length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
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

    public record PortalRequest(UUID playerId,
                                String targetServer,
                                String sourcePortal,
                                long issuedAtMs,
                                String nonce,
                                byte[] signature) {
        public PortalRequest {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(targetServer, "targetServer");
            Objects.requireNonNull(sourcePortal, "sourcePortal");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(signature, "signature");
        }

        public PortalRequestUnsigned unsigned() {
            return new PortalRequestUnsigned(playerId, targetServer, sourcePortal, issuedAtMs, nonce);
        }
    }

    public record PortalRequestUnsigned(UUID playerId,
                                        String targetServer,
                                        String sourcePortal,
                                        long issuedAtMs,
                                        String nonce) {
        public PortalRequestUnsigned {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(targetServer, "targetServer");
            Objects.requireNonNull(sourcePortal, "sourcePortal");
            Objects.requireNonNull(nonce, "nonce");
        }
    }
}
