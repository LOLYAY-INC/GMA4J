package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.packets.system.PacketPing;
import io.lolyay.gma4j.packets.system.PacketPong;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors connection latency using ping/pong packets.
 */
public class LatencyMonitor {
    private final ClientPacketSocket socket;
    private final Map<Integer, Long> pendingPings = new ConcurrentHashMap<>();
    private final AtomicInteger sequenceCounter = new AtomicInteger(0);
    private volatile long lastLatency = -1;
    private volatile long averageLatency = -1;
    private volatile int pingsSent = 0;
    private volatile int pongsReceived = 0;

    public LatencyMonitor(ClientPacketSocket socket) {
        this.socket = socket;
    }

    /**
     * Send a ping packet to measure latency.
     */
    public void sendPing() {
        if (!socket.isConnected()) {
            return;
        }

        int seq = sequenceCounter.incrementAndGet();
        long timestamp = System.currentTimeMillis();
        pendingPings.put(seq, timestamp);
        
        try {
            socket.sendPacket(new PacketPing(timestamp, seq));
            pingsSent++;
        } catch (Exception e) {
            pendingPings.remove(seq);
            System.err.println("Failed to send ping: " + e.getMessage());
        }
    }

    /**
     * Handle a pong response.
     */
    public void handlePong(PacketPong pong) {
        Long sentTime = pendingPings.remove(pong.getSequenceId());
        if (sentTime == null) {
            return; // Unknown or duplicate pong
        }

        long now = System.currentTimeMillis();
        long latency = now - sentTime;
        
        lastLatency = latency;
        pongsReceived++;
        
        // Calculate moving average
        if (averageLatency < 0) {
            averageLatency = latency;
        } else {
            averageLatency = (averageLatency * 7 + latency) / 8; // Exponential moving average
        }
    }

    /**
     * Get the last measured latency in milliseconds (-1 if no data).
     */
    public long getLastLatency() {
        return lastLatency;
    }

    /**
     * Get the average latency in milliseconds (-1 if no data).
     */
    public long getAverageLatency() {
        return averageLatency;
    }

    /**
     * Get the number of pings sent.
     */
    public int getPingsSent() {
        return pingsSent;
    }

    /**
     * Get the number of pongs received.
     */
    public int getPongsReceived() {
        return pongsReceived;
    }

    /**
     * Get packet loss percentage.
     */
    public double getPacketLoss() {
        if (pingsSent == 0) {
            return 0.0;
        }
        return ((pingsSent - pongsReceived) / (double) pingsSent) * 100.0;
    }

    /**
     * Clear all statistics.
     */
    public void reset() {
        pendingPings.clear();
        lastLatency = -1;
        averageLatency = -1;
        pingsSent = 0;
        pongsReceived = 0;
        sequenceCounter.set(0);
    }

    @Override
    public String toString() {
        return String.format("Latency{last=%dms, avg=%dms, loss=%.1f%%}", 
            lastLatency, averageLatency, getPacketLoss());
    }
}
