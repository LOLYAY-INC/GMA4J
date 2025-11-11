package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodec;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

/**
 * Jetty WebSocket client endpoint.
 */
@WebSocket
public class ClientWebSocketHandler {
    private final ClientPacketSocket packetSocket;
    private final PacketHandler packetHandler;

    public ClientWebSocketHandler(ClientPacketSocket packetSocket, PacketHandler packetHandler) {
        this.packetSocket = packetSocket;
        this.packetHandler = packetHandler;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        System.out.println("Connected to server: " + session.getRemoteAddress());
        packetSocket.setSession(session);
        packetHandler.onConnect(packetSocket);
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        try {
            Packet packet = PacketCodec.decode(message);
            packetHandler.onPacket(packetSocket, packet);
        } catch (Exception e) {
            System.err.println("Failed to handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        System.out.println("Disconnected from server (" + statusCode + "): " + reason);
        packetHandler.onDisconnect(packetSocket);
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        System.err.println("WebSocket error: " + error.getMessage());
        error.printStackTrace();
    }

    /**
     * Interface for handling packet events on the client.
     */
    public interface PacketHandler {
        void onConnect(ClientPacketSocket socket);
        void onPacket(ClientPacketSocket socket, Packet packet);
        void onDisconnect(ClientPacketSocket socket);
    }
}
