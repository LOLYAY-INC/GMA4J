package io.lolyay.gma4j.net.server.transport.ws;

import io.lolyay.gma4j.net.codec.connection.server.ServerClientHandler;
import io.lolyay.gma4j.net.codec.connection.server.ServerConnectionListener;
import io.lolyay.gma4j.net.transport.IServerTransport;
import io.lolyay.gma4j.net.transport.ServerTransportData;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;

import static io.lolyay.gma4j.net.transport.TransportConstants.HEADER_GMA_URI;
import static io.lolyay.gma4j.net.transport.TransportConstants.HEADER_UPGRADE;
import static io.lolyay.gma4j.net.transport.TransportConstants.UPGRADE_GMA4J;

public class WsServerTransport implements IServerTransport {

    private final WebSocketServer server;

    public WsServerTransport(ServerTransportData data, ServerClientHandler clientHandler) {
        this.server = new WebSocketServer(new InetSocketAddress(data.host(), data.port())) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                ServerConnectionListener listener = clientHandler.getOrCreateClient(remoteId(conn));
                conn.setAttachment(listener);
                listener.onConnectionEstablished(new WsServerConnection(conn));
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                ServerConnectionListener listener = conn.getAttachment();
                if (listener != null) {
                    listener.onConnectionClosed(reason);
                }
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
            }

            @Override
            public void onMessage(WebSocket conn, ByteBuffer message) {
                ServerConnectionListener listener = conn.getAttachment();
                if (listener != null) {
                    byte[] data = new byte[message.remaining()];
                    message.get(data);
                    listener.onConnectionReceive(data);
                }
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                if (conn == null) {
                    return;
                }
                ServerConnectionListener listener = conn.getAttachment();
                if (listener != null) {
                    listener.onConnectionError(ex);
                }
            }

            @Override
            public void onStart() {
            }

            @Override
            public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
                ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
                if (data.useUpgradeRedirection() && data.upgradeUri() != null && requestsGma4jUpgrade(request)) {
                    builder.put(HEADER_GMA_URI, data.upgradeUri());
                }
                return builder;
            }
        };
    }

    @Override
    public void start() {
        server.start();
    }

    @Override
    public void stop() {
        try {
            server.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping WebSocket server", e);
        }
    }

    private static String remoteId(WebSocket conn) {
        InetSocketAddress address = conn.getRemoteSocketAddress();
        return address != null ? address.toString() : UUID.randomUUID().toString();
    }

    private static boolean requestsGma4jUpgrade(ClientHandshake request) {
        String upgrade = request.getFieldValue(HEADER_UPGRADE);
        return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains(UPGRADE_GMA4J.toLowerCase(Locale.ROOT));
    }
}
