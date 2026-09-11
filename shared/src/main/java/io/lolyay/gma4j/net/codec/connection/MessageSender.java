package io.lolyay.gma4j.net.codec.connection;

public interface MessageSender {
    boolean send(byte[] data);

    /**
     * Urgent skips any outbound coalescing
     */
    default boolean send(byte[] data, boolean urgent) {
        return send(data);
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
