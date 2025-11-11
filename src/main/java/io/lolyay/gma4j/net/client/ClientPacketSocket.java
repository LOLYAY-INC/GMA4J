package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodec;
import io.lolyay.gma4j.net.PacketCodecV2;
import io.lolyay.gma4j.net.PacketSocket;
import org.eclipse.jetty.websocket.api.Session;

import java.io.IOException;

/**
 * Client-side PacketSocket that wraps a Jetty WebSocket Session.
 */
public class ClientPacketSocket implements PacketSocket {
    private Session session;
    private int compressionThreshold = -1; // Disabled by default

    public void setSession(Session session) {
        this.session = session;
    }

    public void setCompressionThreshold(int threshold) {
        this.compressionThreshold = threshold;
    }

    @Override
    public void sendPacket(Packet packet) {
        if (session == null || !session.isOpen()) {
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

    public boolean isConnected() {
        return session != null && session.isOpen();
    }
}
