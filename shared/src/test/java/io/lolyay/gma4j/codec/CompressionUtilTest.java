package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.net.codec.CompressionUtil;
import org.junit.jupiter.api.Test;

import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressionUtilTest {

    private static byte[] deterministic(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 2654435761L) >>> 24);
        }
        return data;
    }

    @Test
    void roundTripVariousSizes() throws DataFormatException {
        for (int size : new int[]{0, 1, 15, 256, 8192, 100_000, 2_000_000}) {
            byte[] data = deterministic(size);
            byte[] back = CompressionUtil.decompress(CompressionUtil.compress(data));
            assertArrayEquals(data, back, "round trip failed for size " + size);
        }
    }

    @Test
    void highlyCompressible() throws DataFormatException {
        byte[] data = new byte[1_000_000];
        byte[] compressed = CompressionUtil.compress(data);
        assertTrue(compressed.length < data.length / 20, "zeros should compress hard, was " + compressed.length);
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
    void invalidDataThrows() {
        assertThrows(DataFormatException.class,
                () -> CompressionUtil.decompress(new byte[]{-1, -1, -1, -1, -1, -1}));
    }
}
