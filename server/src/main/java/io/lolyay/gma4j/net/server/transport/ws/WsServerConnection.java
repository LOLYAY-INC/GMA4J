package io.lolyay.gma4j.net.server.transport.ws;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.connection.WebSocketOutboundBudget;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.framing.CloseFrame;

import java.util.Objects;

public class WsServerConnection implements MessageSender {

    private static final String OUTPUT_FAILURE_REASON = "Outbound WebSocket queue failure";

    private final WebSocketImpl connection;
    private final WebSocketOutboundBudget outboundBudget;

    public WsServerConnection(WebSocket connection) {
        Objects.requireNonNull(connection, "connection");
        if (!(connection instanceof WebSocketImpl webSocketConnection)) {
            throw new IllegalArgumentException("Java-WebSocket 1.6 WebSocketImpl is required");
        }
        this.connection = webSocketConnection;
        this.outboundBudget = new WebSocketOutboundBudget(
                webSocketConnection.outQueue,
                SharedConfig.MAX_PENDING_OUTBOUND_BYTES,
                SharedConfig.MAX_PENDING_OUTBOUND_PACKETS,
                SharedConfig.MAX_PACKET_SIZE,
                false);
    }

    @Override
    public boolean send(byte[] data) {
        if (!connection.isOpen()) {
            return false;
        }
        try {
            if (!outboundBudget.trySend(data.length, () -> connection.send(data))) {
                failClose();
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            failClose();
            return false;
        }
    }

    @Override
    public void close() {
        connection.close();
    }

    private void failClose() {
        connection.closeConnection(CloseFrame.ABNORMAL_CLOSE, OUTPUT_FAILURE_REASON);
    }
}
