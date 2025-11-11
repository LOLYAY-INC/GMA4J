package io.lolyay.gma4j.packets.system;

import io.lolyay.gma4j.net.Packet;

/**
 * System packet for measuring latency.
 * Client sends this, server responds with PacketPong.
 */
public class PacketPing implements Packet {
    private long timestamp;
    private int sequenceId;

    public PacketPing() {}

    public PacketPing(long timestamp, int sequenceId) {
        this.timestamp = timestamp;
        this.sequenceId = sequenceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getSequenceId() {
        return sequenceId;
    }

    @Override
    public String toString() {
        return "PacketPing{timestamp=" + timestamp + ", seq=" + sequenceId + "}";
    }
}
