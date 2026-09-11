package io.lolyay.gma4j.net.codec.connection;

import java.util.concurrent.CompletableFuture;

public interface MessageSender {
    boolean send(byte[] data);

    /**
     * Urgent skips any outbound coalescing
     */
    default boolean send(byte[] data, boolean urgent) {
        return send(data);
    }

    /**
     * Completes when the transport has written the data. Transports without
     * flush feedback complete at hand-off.
     */
    default CompletableFuture<Void> sendWithCompletion(byte[] data, boolean urgent) {
        return send(data, urgent)
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException("Send failed"));
    }

    /**
     * Runtime transport tuning, e.g. TCP_NODELAY
     */
    default void applyModes(boolean lowLatency, boolean bigSize) {
    }

    /**
     * Hard frame cap of the transport, big size grants are clamped to it
     */
    default int maxSupportedFrameSize() {
        return Integer.MAX_VALUE;
    }

    void close();
}
