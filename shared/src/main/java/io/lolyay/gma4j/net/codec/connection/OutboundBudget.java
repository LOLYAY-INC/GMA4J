package io.lolyay.gma4j.net.codec.connection;

import java.util.concurrent.atomic.AtomicBoolean;

public final class OutboundBudget {
    private final long maxPendingBytes;
    private final int maxPendingPackets;
    private long pendingBytes;
    private int pendingPackets;

    public OutboundBudget(long maxPendingBytes, int maxPendingPackets) {
        if (maxPendingBytes <= 0) {
            throw new IllegalArgumentException("MAX_PENDING_OUTBOUND_BYTES must be positive");
        }
        if (maxPendingPackets <= 0) {
            throw new IllegalArgumentException("MAX_PENDING_OUTBOUND_PACKETS must be positive");
        }
        this.maxPendingBytes = maxPendingBytes;
        this.maxPendingPackets = maxPendingPackets;
    }

    public synchronized Reservation tryReserve(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Outbound reservation bytes cannot be negative");
        }
        if (pendingPackets >= maxPendingPackets || bytes > maxPendingBytes - pendingBytes) {
            return null;
        }
        pendingBytes += bytes;
        pendingPackets++;
        return new Reservation(this, bytes);
    }

    public synchronized long pendingBytes() {
        return pendingBytes;
    }

    public synchronized int pendingPackets() {
        return pendingPackets;
    }

    public static long protobufFrameBytes(int payloadBytes) {
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("Payload bytes cannot be negative");
        }
        int remaining = payloadBytes;
        int prefixBytes = 1;
        while ((remaining & ~0x7F) != 0) {
            remaining >>>= 7;
            prefixBytes++;
        }
        return (long) payloadBytes + prefixBytes;
    }

    private synchronized void release(long bytes) {
        pendingBytes -= bytes;
        pendingPackets--;
        if (pendingBytes < 0 || pendingPackets < 0) {
            throw new IllegalStateException("Outbound reservation accounting underflow");
        }
    }

    public static final class Reservation implements AutoCloseable {
        private final OutboundBudget budget;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(OutboundBudget budget, long bytes) {
            this.budget = budget;
            this.bytes = bytes;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                budget.release(bytes);
            }
        }
    }
}
