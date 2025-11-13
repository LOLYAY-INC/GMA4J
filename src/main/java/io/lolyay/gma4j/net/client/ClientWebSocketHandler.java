package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodec;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * Java-WebSocket client endpoint that bridges events to PacketHandler callbacks.
 */
public class ClientWebSocketHandler extends WebSocketClient {
    private final ClientPacketSocket packetSocket;
    private final PacketHandler packetHandler;

    public ClientWebSocketHandler(URI serverUri, ClientPacketSocket packetSocket, PacketHandler packetHandler) {
        super(serverUri);
        this.packetSocket = packetSocket;
        this.packetHandler = packetHandler;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("Connected to server: " + getURI());
        packetSocket.setConnection(this);
        packetHandler.onConnect(packetSocket);
    }

    @Override
    public void onMessage(String message) {
        try {
            Packet packet = PacketCodec.decode(message);
            packetHandler.onPacket(packetSocket, packet);
        } catch (Exception e) {
            System.err.println("Failed to handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("Disconnected from server (" + code + "): " + reason);
        packetHandler.onDisconnect(packetSocket);
        packetSocket.clearConnection();
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("WebSocket error: " + ex.getMessage());
        ex.printStackTrace();
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
