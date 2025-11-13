package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodecV2;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * Enhanced Java-WebSocket client endpoint with compression support.
 */
public class EnhancedWebSocketHandler extends WebSocketClient {
    private final ClientPacketSocket packetSocket;
    private final GMA4JClientSettings settings;
    private final LatencyMonitor latencyMonitor;
    private final ClientWebSocketHandler.PacketHandler packetHandler;

    public EnhancedWebSocketHandler(
            URI serverUri,
            ClientPacketSocket packetSocket,
            GMA4JClientSettings settings,
            LatencyMonitor latencyMonitor,
            ClientWebSocketHandler.PacketHandler packetHandler) {
        super(serverUri);
        this.packetSocket = packetSocket;
        this.settings = settings;
        this.latencyMonitor = latencyMonitor;
        this.packetHandler = packetHandler;

        long idleTimeoutSeconds = Math.max(1, settings.getConnectionTimeout().multipliedBy(2).getSeconds());
        setConnectionLostTimeout((int) idleTimeoutSeconds);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[Client] WebSocket connected: " + getURI());
        packetSocket.setConnection(this);
        packetHandler.onConnect(packetSocket);
    }

    @Override
    public void onMessage(String message) {
        try {
            Packet packet = PacketCodecV2.decode(message);
            packetHandler.onPacket(packetSocket, packet);
        } catch (Exception e) {
            System.err.println("[Client] Failed to handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[Client] WebSocket closed (" + code + "): " + reason);
        packetHandler.onDisconnect(packetSocket);
        packetSocket.clearConnection();
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[Client] WebSocket error: " + ex.getMessage());
        ex.printStackTrace();
    }
}
