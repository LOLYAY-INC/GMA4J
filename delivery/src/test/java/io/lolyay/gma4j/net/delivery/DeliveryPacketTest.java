package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.shared.CodecType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryPacketTest {
    @Test
    void transferCodecRoundTripsAndCopiesPayload() {
        UUID id = UUID.randomUUID();
        byte[] source = {1, 2, 3};
        DeliveryTransferPacket packet = new DeliveryTransferPacket(id, source);
        source[0] = 9;

        byte[] encoded = DeliveryTransferPacket.TYPE.codec().serialize(packet);
        DeliveryTransferPacket decoded = DeliveryTransferPacket.TYPE.codec()
                .deserialize(encoded, CodecType.BINARY_CUSTOM);

        assertEquals(id, decoded.transferId());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.payload());
        assertNotSame(decoded.payload(), decoded.payload());
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryTransferPacket.TYPE.codec().deserialize(
                        Arrays.copyOf(encoded, encoded.length + 1), CodecType.BINARY_CUSTOM));
    }

    @Test
    void ackCodecRoundTripsAndCopiesDigest() {
        UUID id = UUID.randomUUID();
        byte[] digest = DeliveryDigest.sha256(new byte[]{4, 5});
        DeliveryAckPacket packet = new DeliveryAckPacket(id, digest);
        digest[0] ^= 1;

        byte[] encoded = DeliveryAckPacket.TYPE.codec().serialize(packet);
        DeliveryAckPacket decoded = DeliveryAckPacket.TYPE.codec()
                .deserialize(encoded, CodecType.BINARY_CUSTOM);

        assertEquals(id, decoded.transferId());
        assertArrayEquals(DeliveryDigest.sha256(new byte[]{4, 5}), decoded.digest());
        assertNotSame(decoded.digest(), decoded.digest());
        assertThrows(IllegalArgumentException.class,
                () -> new DeliveryAckPacket(id, new byte[31]));
    }

    @Test
    void transferCodecRejectsOversizedPayload() {
        assertThrows(IllegalArgumentException.class, () -> new DeliveryTransferPacket(
                UUID.randomUUID(), new byte[DeliveryLimits.CONSERVATIVE_MAX_PAYLOAD_BYTES + 1]));
    }
}
