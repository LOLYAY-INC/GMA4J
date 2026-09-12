package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.codec.connection.WebSocketFrameGuard;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebSocketFrameGuardTest {

    private static final int BINARY = 0x2;
    private static final int CONTINUATION = 0x0;
    private static final int PING = 0x9;

    @Test
    void framesWithinLimitPassAndLeavePositionUntouched() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 1024);
        ByteBuffer buffer = ByteBuffer.wrap(concat(frame(BINARY, true, 10, true), frame(BINARY, true, 300, false)));
        buffer.position(0);
        assertDoesNotThrow(() -> guard.inspect(buffer));
        assertEquals(0, buffer.position());
    }

    @Test
    void oversizedHeaderIsRejectedBeforeAnyPayloadArrives() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 1024);
        byte[] header = header(BINARY, true, 1025, false);
        assertThrows(PacketCodingException.class, () -> guard.inspect(ByteBuffer.wrap(header)));
    }

    @Test
    void sixtyFourBitLengthIsBounded() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 1 << 20);
        assertThrows(PacketCodingException.class,
                () -> guard.inspect(ByteBuffer.wrap(header(BINARY, true, 1L << 40, true))));
        WebSocketFrameGuard negative = new WebSocketFrameGuard(() -> 1 << 20);
        assertThrows(PacketCodingException.class,
                () -> negative.inspect(ByteBuffer.wrap(header(BINARY, true, Long.MIN_VALUE, true))));
    }

    @Test
    void headerSplitAcrossReadsIsReassembled() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 100);
        byte[] header = header(BINARY, true, 200, true);
        for (int i = 0; i < header.length - 1; i++) {
            byte[] single = {header[i]};
            assertDoesNotThrow(() -> guard.inspect(ByteBuffer.wrap(single)));
        }
        byte[] last = {header[header.length - 1]};
        assertThrows(PacketCodingException.class, () -> guard.inspect(ByteBuffer.wrap(last)));
    }

    @Test
    void payloadBytesAreSkippedAcrossReads() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 64);
        byte[] wire = concat(frame(BINARY, true, 40, true), frame(BINARY, true, 40, true));
        // feed in odd sized chunks so payload and header boundaries never align with a read
        for (int offset = 0; offset < wire.length; offset += 7) {
            ByteBuffer chunk = ByteBuffer.wrap(wire, offset, Math.min(7, wire.length - offset));
            assertDoesNotThrow(() -> guard.inspect(chunk));
        }
        assertThrows(PacketCodingException.class,
                () -> guard.inspect(ByteBuffer.wrap(header(BINARY, true, 65, true))));
    }

    @Test
    void fragmentsMustNotAddUpPastTheLimit() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 100);
        assertDoesNotThrow(() -> guard.inspect(ByteBuffer.wrap(frame(BINARY, false, 60, false))));
        assertDoesNotThrow(() -> guard.inspect(ByteBuffer.wrap(frame(PING, true, 5, false))));
        assertThrows(PacketCodingException.class,
                () -> guard.inspect(ByteBuffer.wrap(frame(CONTINUATION, true, 41, false))));
    }

    @Test
    void finishedMessageResetsTheFragmentTotal() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 100);
        assertDoesNotThrow(() -> guard.inspect(ByteBuffer.wrap(frame(BINARY, false, 60, false))));
        assertDoesNotThrow(() -> guard.inspect(ByteBuffer.wrap(frame(CONTINUATION, true, 40, false))));
        assertDoesNotThrow(() -> guard.inspect(ByteBuffer.wrap(frame(BINARY, true, 100, false))));
    }

    @Test
    void limitIsReadPerFrame() {
        AtomicInteger limit = new AtomicInteger(16);
        WebSocketFrameGuard guard = new WebSocketFrameGuard(limit::get);
        assertThrows(PacketCodingException.class,
                () -> guard.inspect(ByteBuffer.wrap(header(BINARY, true, 32, false))));
        limit.set(64);
        assertDoesNotThrow(() -> new WebSocketFrameGuard(limit::get)
                .inspect(ByteBuffer.wrap(frame(BINARY, true, 32, false))));
    }

    private static byte[] frame(int opcode, boolean fin, int payload, boolean masked) {
        return concat(header(opcode, fin, payload, masked), new byte[payload]);
    }

    private static byte[] header(int opcode, boolean fin, long payload, boolean masked) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | opcode);
        int maskBit = masked ? 0x80 : 0;
        if (payload >= 0 && payload <= 125) {
            out.write(maskBit | (int) payload);
        } else if (payload >= 0 && payload <= 65_535) {
            out.write(maskBit | 126);
            out.write((int) (payload >>> 8));
            out.write((int) payload);
        } else {
            out.write(maskBit | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (payload >>> shift));
            }
        }
        if (masked) {
            out.writeBytes(new byte[]{1, 2, 3, 4});
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}
