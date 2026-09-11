package io.lolyay.gma4j.net.codec.connection;

import io.lolyay.gma4j.net.shared.SharedConfig;
import lombok.Getter;

/**
 * Per connection mode state. Receive allowances are monotonic so frames
 * that were in flight during a downgrade never kill the connection.
 */
public final class ConnectionSettings {
    @Getter
    private final int basePacketSize = SharedConfig.MAX_PACKET_SIZE;
    @Getter
    private volatile boolean lowLatency;
    @Getter
    private volatile boolean bigSize;
    private volatile int sendLimit;
    private volatile int receiveLimit;
    private volatile boolean urgentReceiveAllowed;

    public ConnectionSettings() {
        this.sendLimit = basePacketSize;
        this.receiveLimit = basePacketSize;
    }

    public synchronized void apply(boolean lowLatency, boolean bigSize, int maxPacketSize) {
        this.lowLatency = lowLatency;
        this.bigSize = bigSize;
        int granted = bigSize ? Math.max(basePacketSize, maxPacketSize) : basePacketSize;
        this.sendLimit = granted;
        this.receiveLimit = Math.max(this.receiveLimit, granted);
        if (lowLatency) {
            this.urgentReceiveAllowed = true;
        }
    }

    public int sendLimit() {
        return sendLimit;
    }

    public int receiveLimit() {
        return receiveLimit;
    }

    public boolean isUrgentReceiveAllowed() {
        return urgentReceiveAllowed;
    }

    public boolean isBigReceiveAllowed() {
        return receiveLimit > basePacketSize;
    }
}
