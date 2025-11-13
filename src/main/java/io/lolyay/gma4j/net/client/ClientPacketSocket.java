package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodec;
import io.lolyay.gma4j.net.PacketCodecV2;
import io.lolyay.gma4j.net.PacketSocket;
import org.java_websocket.WebSocket;

/**
 * Client-side PacketSocket that wraps a Java-WebSocket connection.
 */
public class ClientPacketSocket implements PacketSocket {
    private WebSocket connection;
    private int compressionThreshold = -1; // Disabled by default

    public void setConnection(WebSocket connection) {
        this.connection = connection;
    }

    public void clearConnection() {
        this.connection = null;
    }

    public void setCompressionThreshold(int threshold) {
        this.compressionThreshold = threshold;
    }

    @Override
    public void sendPacket(Packet packet) {
        if (connection == null || !connection.isOpen()) {
            throw new IllegalStateException("Session is not open");
        }
        
        String json;
        if (compressionThreshold >= 0) {
            json = PacketCodecV2.encode(packet, compressionThreshold);
        } else {
            json = PacketCodec.encode(packet);
        }
        
        try {
            connection.send(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send packet", e);
        }
    }

    public WebSocket getConnection() {
        return connection;
    }

    public boolean isConnected() {
        return connection != null && connection.isOpen();
    }
}
