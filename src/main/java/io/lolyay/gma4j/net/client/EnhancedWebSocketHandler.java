package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodecV2;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import java.time.Duration;

/**
 * Enhanced Jetty WebSocket client endpoint with compression support.
 */
@WebSocket
public class EnhancedWebSocketHandler {
    private final ClientPacketSocket packetSocket;
    private final ClientSettings settings;
    private final LatencyMonitor latencyMonitor;
    private final ClientWebSocketHandler.PacketHandler packetHandler;

    public EnhancedWebSocketHandler(
            ClientPacketSocket packetSocket,
            ClientSettings settings,
            LatencyMonitor latencyMonitor,
            ClientWebSocketHandler.PacketHandler packetHandler) {
        this.packetSocket = packetSocket;
        this.settings = settings;
        this.latencyMonitor = latencyMonitor;
        this.packetHandler = packetHandler;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        System.out.println("[Client] WebSocket connected: " + session.getRemoteAddress());
        
        // Configure session timeouts
        session.setIdleTimeout(Duration.ofMillis(settings.getConnectionTimeout().toMillis() * 2));
        
        packetSocket.setSession(session);
        packetHandler.onConnect(packetSocket);
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        try {
            Packet packet = PacketCodecV2.decode(message);
            packetHandler.onPacket(packetSocket, packet);
        } catch (Exception e) {
            System.err.println("[Client] Failed to handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        System.out.println("[Client] WebSocket closed (" + statusCode + "): " + reason);
        packetHandler.onDisconnect(packetSocket);
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        System.err.println("[Client] WebSocket error: " + error.getMessage());
        error.printStackTrace();
    }
}
