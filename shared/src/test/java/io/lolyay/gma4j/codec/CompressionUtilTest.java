package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.net.codec.CompressionUtil;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressionUtilTest {

    @Test
    void roundTripWithinPacketLimit() throws DataFormatException {
        int max = SharedConfig.MAX_PACKET_SIZE;
        for (int size : new int[]{0, 1, 15, 256, 8192, 100_000, max - 1, max}) {
            byte[] data = deterministic(size);
            assertArrayEquals(data, CompressionUtil.decompress(CompressionUtil.compress(data)));
        }
    }

    @Test
    void rejectsOutputAbovePacketLimitAcrossScratchSizes() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        int scratch = SharedConfig.COMPRESSION_SHARED_BUFFER_SIZE;
        try {
            SharedConfig.MAX_PACKET_SIZE = 10_000;
            for (int size : new int[]{1, 127, 1024, 4093}) {
                SharedConfig.COMPRESSION_SHARED_BUFFER_SIZE = size;
                byte[] compressed = CompressionUtil.compress(new byte[10_001]);
                assertThrows(DataFormatException.class, () -> CompressionUtil.decompress(compressed));
            }
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
            SharedConfig.COMPRESSION_SHARED_BUFFER_SIZE = scratch;
        }
    }

    @Test
    void highlyCompressibleDataStaysSmall() throws DataFormatException {
        byte[] data = new byte[1_000_000];
        byte[] compressed = CompressionUtil.compress(data);
        assertTrue(compressed.length < data.length / 20);
        assertArrayEquals(data, CompressionUtil.decompress(compressed));
    }

    @Test
    void repeatingPatternCompresses() throws DataFormatException {
        byte[] data = new byte[500_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 16);
        }
        byte[] compressed = CompressionUtil.compress(data);
        assertTrue(compressed.length < data.length);
        assertArrayEquals(data, CompressionUtil.decompress(compressed));
    }

    @Test
    void truncatedDataThrows() {
        byte[] compressed = CompressionUtil.compress(deterministic(100_000));
        byte[] truncated = Arrays.copyOf(compressed, compressed.length - 1);
        assertThrows(DataFormatException.class, () -> CompressionUtil.decompress(truncated));
    }

    @Test
    void dictionaryDataThrows() {
        byte[] dictionary = "shared-dictionary".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        deflater.setDictionary(dictionary);
        deflater.setInput("shared-dictionary-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[128];
        while (!deflater.finished()) {
            int written = deflater.deflate(buffer);
            out.write(buffer, 0, written);
        }
        deflater.end();
        assertThrows(DataFormatException.class, () -> CompressionUtil.decompress(out.toByteArray()));
    }

    @Test
    void invalidDataThrows() {
        assertThrows(DataFormatException.class,
                () -> CompressionUtil.decompress(new byte[]{-1, -1, -1, -1, -1, -1}));
    }

    @Test
    void invalidScratchSizeThrows() {
        int scratch = SharedConfig.COMPRESSION_SHARED_BUFFER_SIZE;
        try {
            SharedConfig.COMPRESSION_SHARED_BUFFER_SIZE = 0;
            assertThrows(IllegalStateException.class, () -> CompressionUtil.compress(new byte[1]));
        } finally {
            SharedConfig.COMPRESSION_SHARED_BUFFER_SIZE = scratch;
        }
    }

    private static byte[] deterministic(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 2654435761L) >>> 24);
        }
        return data;
    }
}
