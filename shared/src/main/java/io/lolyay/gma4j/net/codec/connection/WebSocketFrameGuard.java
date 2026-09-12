package io.lolyay.gma4j.net.codec.connection;

import io.lolyay.gma4j.net.codec.PacketCodingException;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Shadows RFC 6455 framing ahead of the WebSocket decoder so a frame or
 * fragmented message over the connection limit is refused from its header,
 * before the decoder allocates the payload. One instance per connection.
 */
public final class WebSocketFrameGuard {
    private static final int MAX_HEADER_BYTES = 14; // 2 + 8 length + 4 mask
    private static final int CONTROL_OPCODE = 0x8;

    private final IntSupplier limit;
    private final byte[] header = new byte[MAX_HEADER_BYTES];
    private int headerBytes;
    private long payloadRemaining;
    private long messageBytes;

    public WebSocketFrameGuard(IntSupplier limit) {
        this.limit = Objects.requireNonNull(limit, "limit");
    }

    /** Walks every byte the peer sent, the buffer position is left untouched */
    public void inspect(ByteBuffer buffer) {
        int position = buffer.position();
        int end = buffer.limit();
        while (position < end) {
            if (payloadRemaining > 0) {
                int skip = (int) Math.min(payloadRemaining, end - position);
                payloadRemaining -= skip;
                position += skip;
                continue;
            }
            header[headerBytes++] = buffer.get(position++);
            if (headerBytes >= headerLength()) {
                startFrame();
            }
        }
    }

    /** Header bytes needed for the frame, 2 until the length byte is known */
    private int headerLength() {
        if (headerBytes < 2) {
            return 2;
        }
        int lengthField = header[1] & 0x7F;
        int length = 2 + (lengthField == 126 ? 2 : lengthField == 127 ? 8 : 0);
        return (header[1] & 0x80) != 0 ? length + 4 : length;
    }

    private void startFrame() {
        long payload = payloadLength();
        int max = limit.getAsInt();
        if (payload < 0 || payload > max) {
            throw new PacketCodingException("WebSocket frame of " + Long.toUnsignedString(payload)
                    + " bytes exceeds limit " + max);
        }
        boolean fin = (header[0] & 0x80) != 0;
        boolean control = (header[0] & CONTROL_OPCODE) != 0;
        if (!control) {
            // fragments must not add up past the limit either
            messageBytes += payload;
            if (messageBytes > max) {
                throw new PacketCodingException("WebSocket message of " + messageBytes
                        + " bytes exceeds limit " + max);
            }
            if (fin) {
                messageBytes = 0;
            }
        }
        payloadRemaining = payload;
        headerBytes = 0;
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
