package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.net.codec.connection.WebSocketFrameGuard;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketFrameGuardTest {

    private static final int BINARY = 0x2;
    private static final int CONTINUATION = 0x0;
    private static final int PING = 0x9;
    private static final int OK = -1;

    @Test
    void framesWithinLimitPassAndLeavePositionUntouched() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 1024);
        ByteBuffer buffer = ByteBuffer.wrap(concat(frame(BINARY, true, 10, true), frame(BINARY, true, 300, false)));
        assertEquals(OK, guard.inspect(buffer));
        assertEquals(0, buffer.position());
    }

    @Test
    void oversizedHeaderIsRejectedBeforeAnyPayloadArrives() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 1024);
        byte[] header = header(BINARY, true, 1025, false);
        assertEquals(0, guard.inspect(ByteBuffer.wrap(header)));
        assertNotNull(guard.rejection());
    }

    @Test
    void rejectionReportsTheOffendingHeaderPosition() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 64);
        byte[] small = frame(BINARY, true, 40, true);
        byte[] wire = concat(small, header(BINARY, true, 65, true));
        assertEquals(small.length, guard.inspect(ByteBuffer.wrap(wire)));
    }

    @Test
    void sixtyFourBitLengthIsBounded() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 1 << 20);
        assertEquals(0, guard.inspect(ByteBuffer.wrap(header(BINARY, true, 1L << 40, true))));
        WebSocketFrameGuard negative = new WebSocketFrameGuard(() -> 1 << 20);
        assertEquals(0, negative.inspect(ByteBuffer.wrap(header(BINARY, true, Long.MIN_VALUE, true))));
    }

    @Test
    void headerSplitAcrossReadsIsReassembled() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 100);
        byte[] header = header(BINARY, true, 200, true);
        for (int i = 0; i < header.length - 1; i++) {
            assertEquals(OK, guard.inspect(ByteBuffer.wrap(new byte[]{header[i]})));
        }
        assertEquals(0, guard.inspect(ByteBuffer.wrap(new byte[]{header[header.length - 1]})));
    }

    @Test
    void payloadBytesAreSkippedAcrossReads() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 64);
        byte[] wire = concat(frame(BINARY, true, 40, true), frame(BINARY, true, 40, true));
        // feed in odd sized chunks so payload and header boundaries never align with a read
        for (int offset = 0; offset < wire.length; offset += 7) {
            ByteBuffer chunk = ByteBuffer.wrap(wire, offset, Math.min(7, wire.length - offset));
            assertEquals(OK, guard.inspect(chunk));
        }
        assertEquals(0, guard.inspect(ByteBuffer.wrap(header(BINARY, true, 65, true))));
    }

    @Test
    void fragmentsMustNotAddUpPastTheLimit() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 100);
        assertEquals(OK, guard.inspect(ByteBuffer.wrap(frame(BINARY, false, 60, false))));
        assertEquals(OK, guard.inspect(ByteBuffer.wrap(frame(PING, true, 5, false))));
        assertEquals(0, guard.inspect(ByteBuffer.wrap(frame(CONTINUATION, true, 41, false))));
        assertTrue(guard.rejection().contains("message"));
    }

    @Test
    void finishedMessageResetsTheFragmentTotal() {
        WebSocketFrameGuard guard = new WebSocketFrameGuard(() -> 100);
        assertEquals(OK, guard.inspect(ByteBuffer.wrap(frame(BINARY, false, 60, false))));
        assertEquals(OK, guard.inspect(ByteBuffer.wrap(frame(CONTINUATION, true, 40, false))));
        assertEquals(OK, guard.inspect(ByteBuffer.wrap(frame(BINARY, true, 100, false))));
    }

    /** A rejected header stays pending and is admitted once the limit covers it */
    @Test
    void rejectedHeaderIsAdmittedAfterTheLimitWasRaised() {
        AtomicInteger limit = new AtomicInteger(16);
        WebSocketFrameGuard guard = new WebSocketFrameGuard(limit::get);
        byte[] big = frame(BINARY, true, 32, false);
        byte[] wire = concat(big, frame(BINARY, true, 40, false));
        ByteBuffer buffer = ByteBuffer.wrap(wire);

        assertEquals(0, guard.inspect(buffer));
        assertEquals(0, guard.inspect(buffer), "still rejected while the limit is unchanged");

        limit.set(32);
        assertEquals(big.length, guard.inspect(buffer), "the next frame is now the one over the limit");
        limit.set(64);
        buffer.position(big.length);
        assertEquals(OK, guard.inspect(buffer));
    }

    /** The retried header may have started in an earlier read, only its tail is in this buffer */
    @Test
    void retryOfCarriedOverHeaderStaysAligned() {
        AtomicInteger limit = new AtomicInteger(16);
        WebSocketFrameGuard guard = new WebSocketFrameGuard(limit::get);
        byte[] big = frame(BINARY, true, 300, true);
        byte[] wire = concat(big, frame(BINARY, true, 10, true));
        int split = 5; // inside the 8 byte header
        assertEquals(OK, guard.inspect(ByteBuffer.wrap(wire, 0, split)));

        ByteBuffer rest = ByteBuffer.wrap(wire, split, wire.length - split);
        assertEquals(split, guard.inspect(rest));
        limit.set(300);
        assertEquals(OK, guard.inspect(rest));
        assertEquals(0, guard.inspect(ByteBuffer.wrap(header(BINARY, true, 301, true))));
    }

    /** Payload bytes look like a 64 bit length header, so any misalignment shows up */
    private static byte[] frame(int opcode, boolean fin, int payload, boolean masked) {
        byte[] body = new byte[payload];
        Arrays.fill(body, (byte) 0xFF);
        return concat(header(opcode, fin, payload, masked), body);
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
