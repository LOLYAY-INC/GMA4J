package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.codec.fixtures.BinaryPacket;
import io.lolyay.gma4j.codec.fixtures.BlobPacket;
import io.lolyay.gma4j.net.shared.CodecType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomCodecTest {

    @Test
    void binaryRoundTrip() {
        BinaryPacket packet = new BinaryPacket(-42, 9_000_000_000L, true, "hello 世界 🚀", 300_000);
        byte[] bytes = BinaryPacket.TYPE.codec().serialize(packet);
        assertEquals(packet, BinaryPacket.TYPE.codec().deserialize(bytes, CodecType.BINARY_CUSTOM));
    }

    @Test
    void edgeValues() {
        BinaryPacket packet = new BinaryPacket(Integer.MIN_VALUE, Long.MIN_VALUE, false, "", 0);
        byte[] bytes = BinaryPacket.TYPE.codec().serialize(packet);
        assertEquals(packet, BinaryPacket.TYPE.codec().deserialize(bytes, CodecType.BINARY_CUSTOM));
    }

    @Test
    void largeBlobRoundTrip() {
        byte[] blob = new byte[4_000_000];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i * 31 + 7);
        }
        BlobPacket packet = new BlobPacket(blob, 0xCAFEBABEL);
        byte[] bytes = BlobPacket.TYPE.codec().serialize(packet);
        BlobPacket back = BlobPacket.TYPE.codec().deserialize(bytes, CodecType.BINARY_CUSTOM);
        assertArrayEquals(blob, back.blob());
        assertEquals(0xCAFEBABEL, back.checksum());
    }

    @Test
    void emptyBlobRoundTrip() {
        BlobPacket packet = new BlobPacket(new byte[0], 1L);
        BlobPacket back = BlobPacket.TYPE.codec().deserialize(
                BlobPacket.TYPE.codec().serialize(packet), CodecType.BINARY_CUSTOM);
        assertEquals(0, back.blob().length);
        assertEquals(1L, back.checksum());
    }

    @Test
    void wrongCodecTypeThrows() {
        byte[] bytes = BinaryPacket.TYPE.codec().serialize(new BinaryPacket(1, 1, true, "x", 1));
        assertThrows(IllegalArgumentException.class,
                () -> BinaryPacket.TYPE.codec().deserialize(bytes, CodecType.JSON_GSON));
    }

    @Test
    void truncatedThrows() {
        byte[] bytes = BinaryPacket.TYPE.codec().serialize(new BinaryPacket(1, 2, true, "abc", 4));
        byte[] truncated = Arrays.copyOf(bytes, 3);
        assertThrows(IndexOutOfBoundsException.class,
                () -> BinaryPacket.TYPE.codec().deserialize(truncated, CodecType.BINARY_CUSTOM));
    }
}
