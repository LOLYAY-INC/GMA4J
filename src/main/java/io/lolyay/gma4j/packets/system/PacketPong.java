package io.lolyay.gma4j.packets.system;

import io.lolyay.gma4j.net.Packet;

/**
 * System packet responding to PacketPing.
 * Server sends this in response to ping requests.
 */
public class PacketPong implements Packet {
    private long clientTimestamp;
    private long serverTimestamp;
    private int sequenceId;

    public PacketPong() {}

    public PacketPong(long clientTimestamp, int sequenceId) {
        this.clientTimestamp = clientTimestamp;
        this.serverTimestamp = System.currentTimeMillis();
        this.sequenceId = sequenceId;
    }

    public long getClientTimestamp() {
        return clientTimestamp;
    }

    public long getServerTimestamp() {
        return serverTimestamp;
    }

    public int getSequenceId() {
        return sequenceId;
    }

    @Override
    public String toString() {
        return "PacketPong{clientTs=" + clientTimestamp + ", serverTs=" + serverTimestamp + 
               ", seq=" + sequenceId + "}";
    }
}
