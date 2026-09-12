package io.lolyay.gma4j.net.codec.connection;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Shadows RFC 6455 framing ahead of the WebSocket decoder so a frame or
 * fragmented message over the connection limit is refused from its header,
 * before the decoder allocates the payload. A rejected header stays pending
 * so it can be admitted once the limit was raised. One instance per connection.
 */
public final class WebSocketFrameGuard {
    private static final int MAX_HEADER_BYTES = 14; // 2 + 8 length + 4 mask
    private static final int CONTROL_OPCODE = 0x8;

    private final IntSupplier limit;
    private final byte[] header = new byte[MAX_HEADER_BYTES];
    private int headerBytes;
    private long payloadRemaining;
    private long messageBytes;
    private String rejection;

    public WebSocketFrameGuard(IntSupplier limit) {
        this.limit = Objects.requireNonNull(limit, "limit");
    }

    /**
     * Walks the bytes from the buffer position, which is left untouched.
     * Returns the position of the first frame header over the limit or -1;
     * a header carried over from an earlier read reports the entry position.
     */
    public int inspect(ByteBuffer buffer) {
        int position = buffer.position();
        int end = buffer.limit();
        int headerStart = position;
        while (true) {
            if (headerComplete() && !admit()) {
                return headerStart;
            }
            if (position >= end) {
                return -1;
            }
            if (payloadRemaining > 0) {
                int skip = (int) Math.min(payloadRemaining, end - position);
                payloadRemaining -= skip;
                position += skip;
                continue;
            }
            if (headerBytes == 0) {
                headerStart = position;
            }
            header[headerBytes++] = buffer.get(position++);
        }
    }

    /** Why the last inspect stopped */
    public String rejection() {
        return rejection;
    }

    private boolean headerComplete() {
        return headerBytes >= 2 && headerBytes >= headerLength();
    }

    /** Header bytes needed for the frame, 2 until the length byte is known */
    private int headerLength() {
        int lengthField = header[1] & 0x7F;
        int length = 2 + (lengthField == 126 ? 2 : lengthField == 127 ? 8 : 0);
        return (header[1] & 0x80) != 0 ? length + 4 : length;
    }

    /** Applies the limit to the pending header, on success the frame starts */
    private boolean admit() {
        long payload = payloadLength();
        int max = limit.getAsInt();
        if (payload < 0 || payload > max) {
            rejection = "WebSocket frame of " + Long.toUnsignedString(payload) + " bytes exceeds limit " + max;
            return false;
        }
        boolean fin = (header[0] & 0x80) != 0;
        boolean control = (header[0] & CONTROL_OPCODE) != 0;
        if (!control) {
            // fragments must not add up past the limit either
            if (messageBytes + payload > max) {
                rejection = "WebSocket message of " + (messageBytes + payload) + " bytes exceeds limit " + max;
                return false;
            }
            messageBytes = fin ? 0 : messageBytes + payload;
        }
        payloadRemaining = payload;
        headerBytes = 0;
        return true;
    }

    private long payloadLength() {
        int lengthField = header[1] & 0x7F;
        if (lengthField < 126) {
            return lengthField;
        }
        int lengthBytes = lengthField == 126 ? 2 : 8;
        long length = 0;
        for (int i = 0; i < lengthBytes; i++) {
            length = (length << 8) | (header[2 + i] & 0xFF);
        }
        return length;
    }
}
