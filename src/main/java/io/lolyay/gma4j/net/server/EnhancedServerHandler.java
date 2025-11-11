package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.net.PacketCodecV2;
import io.lolyay.gma4j.packets.system.PacketPing;
import io.lolyay.gma4j.packets.system.PacketPong;
import io.lolyay.gma4j.packets.system.PacketVersion;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

/**
 * Enhanced server handler with automatic system packet handling.
 */
@WebSocket
public class EnhancedServerHandler {
    private final EnhancedPacketHandler packetHandler;
    private final int compressionThreshold;
    private final String serverVersion;

    public EnhancedServerHandler(
            EnhancedPacketHandler packetHandler, 
            int compressionThreshold,
            String serverVersion) {
        this.packetHandler = packetHandler;
        this.compressionThreshold = compressionThreshold;
        this.serverVersion = serverVersion;
    }

    @OnWebSocketConnect
    public void onConnect(Session session) {
        System.out.println("[Server] Client connected: " + session.getRemoteAddress());
        ServerPacketSocket socket = new ServerPacketSocket(session);
        socket.setCompressionThreshold(compressionThreshold);
        
        // Send server version
        socket.sendPacket(new PacketVersion("1.0", "GMA4J-Server", serverVersion));
        
        packetHandler.onConnect(socket);
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        try {
            Packet packet = PacketCodecV2.decode(message);
            ServerPacketSocket socket = new ServerPacketSocket(session);
            socket.setCompressionThreshold(compressionThreshold);
            
            // Handle system packets automatically
            if (packet instanceof PacketPing ping) {
                // Auto-respond to pings
                socket.sendPacket(new PacketPong(ping.getTimestamp(), ping.getSequenceId()));
                return;
            } else if (packet instanceof PacketVersion version) {
                System.out.println("[Server] Client version: " + version);
                packetHandler.onVersionExchange(socket, version);
                return;
            }
            
            // User packets
            packetHandler.onPacket(socket, packet);
        } catch (Exception e) {
            System.err.println("[Server] Failed to handle message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        System.out.println("[Server] Client disconnected: " + session.getRemoteAddress() + 
                           " (" + statusCode + ")");
        ServerPacketSocket socket = new ServerPacketSocket(session);
        packetHandler.onDisconnect(socket);
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        System.err.println("[Server] WebSocket error: " + error.getMessage());
        error.printStackTrace();
    }

    /**
     * Enhanced packet handler interface with version exchange callback.
     */
    public interface EnhancedPacketHandler {
        void onConnect(ServerPacketSocket socket);
        void onPacket(ServerPacketSocket socket, Packet packet);
        void onDisconnect(ServerPacketSocket socket);
        
        /**
         * Called when client version is received.
         */
        default void onVersionExchange(ServerPacketSocket socket, PacketVersion clientVersion) {
            // Override to handle version compatibility checks
        }
    }
}
