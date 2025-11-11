package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodec;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

/**
 * Jetty WebSocket endpoint that handles incoming connections and messages.
 */
@WebSocket
public class ServerWebSocketHandler {
    private final PacketHandler packetHandler;

    public ServerWebSocketHandler(PacketHandler packetHandler) {
        this.packetHandler = packetHandler;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        System.out.println("Client connected: " + session.getRemoteAddress());
        ServerPacketSocket socket = new ServerPacketSocket(session);
        packetHandler.onConnect(socket);
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        try {
            Packet packet = PacketCodec.decode(message);
            ServerPacketSocket socket = new ServerPacketSocket(session);
            packetHandler.onPacket(socket, packet);
        } catch (Exception e) {
            System.err.println("Failed to handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        System.out.println("Client disconnected: " + session.getRemoteAddress() + " (" + statusCode + ")");
        ServerPacketSocket socket = new ServerPacketSocket(session);
        packetHandler.onDisconnect(socket);
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        System.err.println("WebSocket error: " + error.getMessage());
        error.printStackTrace();
    }

    /**
     * Interface for handling packet events on the server.
     */
    public interface PacketHandler {
        void onConnect(ServerPacketSocket socket);
        void onPacket(ServerPacketSocket socket, Packet packet);
        void onDisconnect(ServerPacketSocket socket);
    }
}
