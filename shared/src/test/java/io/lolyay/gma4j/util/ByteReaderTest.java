package io.lolyay.gma4j.util;

import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ByteReaderTest {

    private enum Sample { A, B }

    private static ByteReader reader(int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return new ByteReader(data);
    }

    private static byte[] varInt(int value) {
        ByteWriter writer = new ByteWriter();
        writer.writeVarInt(value);
        byte[] out = new byte[writer.length()];
        System.arraycopy(writer.getBuf(), 0, out, 0, out.length);
        return out;
    }

    @Test
    void varIntOverflowThrows() {
        ByteReader overlong = reader(0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
        assertThrows(IndexOutOfBoundsException.class, overlong::readVarInt);
    }

    @Test
    void varIntFiveByteNegativeRoundTrips() {
        assertEquals(-1, new ByteReader(varInt(-1)).readVarInt());
        assertEquals(Integer.MIN_VALUE, new ByteReader(varInt(Integer.MIN_VALUE)).readVarInt());
    }

    @Test
    void varLongOverflowThrows() {
        ByteReader overlong = reader(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01);
        assertThrows(IndexOutOfBoundsException.class, overlong::readVarLong);
    }

    @Test
    void varLongRoundTrips() {
        ByteWriter writer = new ByteWriter();
        writer.writeVarLong(-1L);
        writer.writeVarLong(Long.MIN_VALUE);
        ByteReader back = new ByteReader(writer.getBuf());
        assertEquals(-1L, back.readVarLong());
        assertEquals(Long.MIN_VALUE, back.readVarLong());
    }

    @Test
    void hugeReadBytesFailsWithoutAllocating() {
        assertTimeoutPreemptively(java.time.Duration.of(2, TimeUnit.SECONDS.toChronoUnit()), () -> {
            ByteReader tiny = reader(1, 2, 3, 4);
            assertThrows(IndexOutOfBoundsException.class, () -> tiny.readBytes(Integer.MAX_VALUE));
        });
    }

    @Test
    void negativeReadBytesThrows() {
        assertThrows(IndexOutOfBoundsException.class, () -> reader(1, 2).readBytes(-5));
    }

    @Test
    void negativePrefixedBytesThrows() {
        ByteReader negative = new ByteReader(varInt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> negative.readPrefixedBytes(16));
    }

    @Test
    void negativeEnumArrayCountThrows() {
        ByteReader negative = new ByteReader(varInt(-1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> negative.readPrefixedEnumArray(Sample.class, 8));
    }

    @Test
    void negativePrefixedArrayCountThrows() {
        ByteReader negative = new ByteReader(varInt(-1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> negative.readPrefixedArray(() -> 0));
    }

    @Test
    void prefixedArrayCountBeyondRemainingThrows() {
        ByteWriter writer = new ByteWriter();
        writer.writeVarInt(1000);
        writer.writeByte(0);
        byte[] out = new byte[writer.length()];
        System.arraycopy(writer.getBuf(), 0, out, 0, out.length);
        ByteReader short_ = new ByteReader(out);
        assertThrows(IndexOutOfBoundsException.class, () -> short_.readPrefixedArray(short_::readByte));
    }

    @Test
    void prefixedArrayRoundTrips() {
        ByteWriter writer = new ByteWriter();
        writer.writePrefixedArray(List.of(3, 5, 7), writer::writeByte);
        byte[] out = new byte[writer.length()];
        System.arraycopy(writer.getBuf(), 0, out, 0, out.length);
        ByteReader back = new ByteReader(out);
        assertEquals(List.of((byte) 3, (byte) 5, (byte) 7), back.readPrefixedArray(back::readByte));
    }
}
