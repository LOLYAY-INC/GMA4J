package io.lolyay.gma4j.net.codec.connection;

import java.util.function.LongSupplier;

/**
 * Caps the frame bytes a process decodes and dispatches at once. Decoding
 * holds copies of a frame transiently, so peak memory is a small multiple
 * of this plus decompression up to each connection's receive limit.
 */
public final class InboundBudget {
    private final LongSupplier maxBytes;
    private long inFlightBytes;

    public InboundBudget(LongSupplier maxBytes) {
        this.maxBytes = maxBytes;
    }

    public synchronized boolean tryAcquire(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Inbound bytes cannot be negative");
        }
        if (bytes > maxBytes.getAsLong() - inFlightBytes) {
            return false;
        }
        inFlightBytes += bytes;
        return true;
    }

    public synchronized void release(int bytes) {
        inFlightBytes -= bytes;
        if (inFlightBytes < 0) {
            throw new IllegalStateException("Inbound budget accounting underflow");
        }
    }

    public synchronized long inFlightBytes() {
        return inFlightBytes;
    }
}
