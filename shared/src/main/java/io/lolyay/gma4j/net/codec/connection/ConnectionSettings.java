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
    private volatile int receiveAllowance;
    private volatile boolean urgentReceiveAllowed;

    public ConnectionSettings() {
        this.sendLimit = basePacketSize;
        this.receiveLimit = basePacketSize;
        this.receiveAllowance = basePacketSize;
    }

    public synchronized void apply(boolean lowLatency, boolean bigSize, int maxPacketSize) {
        this.lowLatency = lowLatency;
        this.bigSize = bigSize;
        int granted = bigSize ? Math.max(basePacketSize, maxPacketSize) : basePacketSize;
        this.sendLimit = granted;
        this.receiveLimit = Math.max(this.receiveLimit, granted);
        this.receiveAllowance = Math.max(this.receiveAllowance, this.receiveLimit);
        if (lowLatency) {
            this.urgentReceiveAllowed = true;
        }
    }

    /** Frame decoder headroom so a grant already in flight is not cut off mid-read */
    public synchronized void raiseReceiveAllowance(int size) {
        int capped = Math.min(Math.max(size, basePacketSize), SharedConfig.MAX_BIG_PACKET_SIZE);
        this.receiveAllowance = Math.max(this.receiveAllowance, capped);
    }

    public int sendLimit() {
        return sendLimit;
    }

    public int receiveLimit() {
        return receiveLimit;
    }

    public int receiveAllowance() {
        return receiveAllowance;
    }

    public boolean isUrgentReceiveAllowed() {
        return urgentReceiveAllowed;
    }

    public boolean isBigReceiveAllowed() {
        return receiveLimit > basePacketSize;
    }
}
