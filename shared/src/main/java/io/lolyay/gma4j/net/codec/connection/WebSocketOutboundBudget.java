package io.lolyay.gma4j.net.codec.connection;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;

public final class WebSocketOutboundBudget {
    private final BlockingQueue<ByteBuffer> outQueue;
    private final long maxPendingBytes;
    private final int maxPendingPackets;
    private final int maxPayloadBytes;
    private final boolean masked;
    private final long writerHeldBytes;

    public WebSocketOutboundBudget(BlockingQueue<ByteBuffer> outQueue, long maxPendingBytes,
                                   int maxPendingPackets, int maxPayloadBytes, boolean masked) {
        this.outQueue = Objects.requireNonNull(outQueue, "outQueue");
        if (maxPendingBytes <= 0) {
            throw new IllegalArgumentException("MAX_PENDING_OUTBOUND_BYTES must be positive");
        }
        if (maxPendingPackets < 2) {
            throw new IllegalArgumentException(
                    "MAX_PENDING_OUTBOUND_PACKETS must be at least 2 for WebSocket output");
        }
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("MAX_PACKET_SIZE must be positive for WebSocket output");
        }
        this.maxPendingBytes = maxPendingBytes;
        this.maxPendingPackets = maxPendingPackets;
        this.maxPayloadBytes = maxPayloadBytes;
        this.masked = masked;
        this.writerHeldBytes = frameCapacity(maxPayloadBytes, masked);
        long minimumBytes = writerHeldBytes + frameCapacity(0, masked);
        if (maxPendingBytes < minimumBytes) {
            throw new IllegalArgumentException(
                    "MAX_PENDING_OUTBOUND_BYTES must be at least " + minimumBytes + " for WebSocket output");
        }
    }

    public synchronized boolean trySend(int payloadBytes, Runnable send) {
        Objects.requireNonNull(send, "send");
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("Payload bytes cannot be negative");
        }
        if (payloadBytes > maxPayloadBytes || !fits(frameCapacity(payloadBytes, masked), true)) {
            return false;
        }
        send.run();
        return fits(0, false);
    }

    public static long frameCapacity(int payloadBytes, boolean masked) {
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("Payload bytes cannot be negative");
        }
        int lengthBytes;
        if (payloadBytes <= 125) {
            lengthBytes = 2;
        } else if (payloadBytes <= 65_535) {
            lengthBytes = 4;
        } else {
            lengthBytes = 10;
        }
        return (long) payloadBytes + lengthBytes + (masked ? 4 : 0);
    }

    private boolean fits(long additionalBytes, boolean additionalPacket) {
        long pendingBytes = writerHeldBytes;
        int pendingPackets = 1;
        if (additionalPacket) {
            pendingPackets++;
            pendingBytes += additionalBytes;
        }
        if (pendingPackets > maxPendingPackets || pendingBytes > maxPendingBytes) {
            return false;
        }
        for (ByteBuffer buffer : outQueue) {
            if (pendingPackets >= maxPendingPackets
                    || buffer.capacity() > maxPendingBytes - pendingBytes) {
                return false;
            }
            pendingPackets++;
            pendingBytes += buffer.capacity();
        }
        return true;
    }
}
