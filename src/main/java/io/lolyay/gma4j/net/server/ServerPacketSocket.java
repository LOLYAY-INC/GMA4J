package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodec;
import io.lolyay.gma4j.net.PacketCodecV2;
import io.lolyay.gma4j.net.PacketSocket;
import org.eclipse.jetty.websocket.api.Session;

import java.io.IOException;

/**
 * Server-side PacketSocket that wraps a Jetty WebSocket Session.
 */
public class ServerPacketSocket implements PacketSocket {
    private final Session session;
    private int compressionThreshold = -1; // Disabled by default

    public ServerPacketSocket(Session session) {
        this.session = session;
    }

    public void setCompressionThreshold(int threshold) {
        this.compressionThreshold = threshold;
    }

    @Override
    public void sendPacket(Packet packet) {
        if (!session.isOpen()) {
            throw new IllegalStateException("Session is not open");
        }
        
        String json;
        if (compressionThreshold >= 0) {
            json = PacketCodecV2.encode(packet, compressionThreshold);
        } else {
            json = PacketCodec.encode(packet);
        }
        
        try {
            session.getRemote().sendString(json);
        } catch (IOException e) {
            throw new RuntimeException("Failed to send packet", e);
        }
    }

    public Session getSession() {
        return session;
    }
}
