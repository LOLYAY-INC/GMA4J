package io.lolyay.gma4j.net.server.transport.ws;

import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.codec.connection.server.ServerClientHandler;
import io.lolyay.gma4j.net.codec.connection.server.ServerConnectionListener;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.transport.IServerTransport;
import io.lolyay.gma4j.net.transport.ServerTransportData;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.protocols.Protocol;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.lolyay.gma4j.net.transport.TransportConstants.*;

public class WsServerTransport implements IServerTransport {

    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final WebSocketServer server;

    public WsServerTransport(ServerTransportData data, ServerClientHandler clientHandler) {
        // static cap is the largest grant possible, the per connection guard enforces the actual allowance
        int frameCap = SharedConfig.ALLOW_BIG_SIZE_MODE
                ? Math.max(SharedConfig.MAX_PACKET_SIZE, SharedConfig.MAX_BIG_PACKET_SIZE)
                : SharedConfig.MAX_PACKET_SIZE;
        FrameGuardedDraft draft = new FrameGuardedDraft(
                Collections.emptyList(),
                Collections.singletonList(new Protocol("")),
                frameCap
        );

        this.server = new WebSocketServer(new InetSocketAddress(data.host(), data.port()), SharedConfig.NETWORK_THREADS, List.of(draft)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                ServerConnectionListener listener = clientHandler.getOrCreateClient(remoteId(conn));
                conn.setAttachment(listener);
                listener.onConnectionEstablished(new WsServerConnection(conn, frameCap));
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
                int limit = incomingLimit(conn);
                if (message.remaining() > limit) {
                    PacketCodingException error = new PacketCodingException(
                            "Packet too large: " + message.remaining() + " > " + limit);
                    if (listener != null) {
                        listener.onConnectionError(error);
                    }
                    conn.close(CloseFrame.TOOBIG, "Packet too large");
                    return;
                }

                if (listener != null) {
                    byte[] data = new byte[message.remaining()];
                    message.get(data);
                    listener.onConnectionReceive(data);
                }
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                if (conn == null) {
                    started.completeExceptionally(ex);
                    return;
                }
                ServerConnectionListener listener = conn.getAttachment();
                if (listener != null) {
                    listener.onConnectionError(ex);
                }
            }

            @Override
            public void onStart() {
                started.complete(null);
            }

            @Override
            public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
                rejectDisallowedOrigin(data.allowedOrigins(), request);
                ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
                if (draft instanceof FrameGuardedDraft guarded) {
                    guarded.bind(conn, () -> incomingLimit(conn));
                }
                if (data.useUpgradeRedirection() && data.upgradeUri() != null && requestsGma4jUpgrade(request)) {
                    builder.put(HEADER_GMA_URI, data.upgradeUri());
                }
                return builder;
            }
        };

        if (data.sslContext() != null) {
            server.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(data.sslContext()));
        }
    }

    /** Browsers send Origin; native clients do not, so an absent Origin is allowed */
    private static void rejectDisallowedOrigin(java.util.Set<String> allowedOrigins, ClientHandshake request)
            throws InvalidDataException {
        if (allowedOrigins.isEmpty()) {
            return;
        }
        String origin = request.getFieldValue(HEADER_ORIGIN);
        if (origin != null && !origin.isEmpty()
                && !allowedOrigins.contains(origin.toLowerCase(Locale.ROOT))) {
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Origin not allowed");
        }
    }

    /** Base limit until the listener is attached in onOpen */
    private static int incomingLimit(WebSocket conn) {
        ServerConnectionListener listener = conn.getAttachment();
        return listener != null ? listener.maxIncomingFrameSize() : SharedConfig.MAX_PACKET_SIZE;
    }

    @Override
    public void start() {
        server.start();
        try {
            started.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            stopAfterFailedStart();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting WebSocket server", e);
        } catch (Exception e) {
            stopAfterFailedStart();
            throw new IllegalStateException("Failed to start WebSocket server", e);
        }
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

    private void stopAfterFailedStart() {
        try {
            server.stop(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
