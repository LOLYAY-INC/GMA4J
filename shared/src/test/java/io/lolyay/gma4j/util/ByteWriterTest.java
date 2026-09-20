package io.lolyay.gma4j.util;

import io.lolyay.gma4j.net.util.ByteWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteWriterTest {

    @Test
    void toByteArrayReturnsExactContentWithoutSlack() {
        ByteWriter writer = new ByteWriter();
        for (int i = 0; i < 17; i++) {
            writer.writeByte(i);
        }

        byte[] trimmed = writer.toByteArray();
        assertEquals(17, trimmed.length);
        assertTrue(writer.getBuf().length >= 64, "backing array keeps spare capacity");
        for (int i = 0; i < 17; i++) {
            assertEquals(i, trimmed[i] & 0xFF);
        }
    }

    @Test
    void toByteArrayCopiesSoLaterWritesDoNotLeak() {
        ByteWriter writer = new ByteWriter();
        writer.writeInt(42);
        byte[] snapshot = writer.toByteArray();
        writer.writeInt(99);

        assertEquals(4, snapshot.length);
        assertEquals(42, ((snapshot[0] & 0xFF) << 24) | ((snapshot[1] & 0xFF) << 16)
                | ((snapshot[2] & 0xFF) << 8) | (snapshot[3] & 0xFF));
    }
}
