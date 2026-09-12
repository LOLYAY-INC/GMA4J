package io.lolyay.gma4j.net.transport.ws;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.connection.WebSocketOutboundBudget;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;

import java.util.Objects;

public class WsClientConnection implements MessageSender {

    private static final String OUTPUT_FAILURE_REASON = "Outbound WebSocket queue failure";

    private final WebSocketClient client;
    private final WebSocketImpl connection;
    private final WebSocketOutboundBudget outboundBudget;

    public WsClientConnection(WebSocketClient client) {
        this(client, SharedConfig.MAX_PACKET_SIZE);
    }

    public WsClientConnection(WebSocketClient client, int frameCap) {
        this.client = Objects.requireNonNull(client, "client");
        WebSocket webSocket = client.getConnection();
        if (!(webSocket instanceof WebSocketImpl webSocketConnection)) {
            throw new IllegalArgumentException("Java-WebSocket 1.6 WebSocketImpl is required");
        }
        this.connection = webSocketConnection;
        // a frame is only sendable if the outbound budget can hold it twice
        int sendableCap = Math.min(frameCap, WebSocketOutboundBudget.largestSendablePayload(
                SharedConfig.MAX_PENDING_OUTBOUND_BYTES, true));
        this.outboundBudget = new WebSocketOutboundBudget(
                webSocketConnection.outQueue,
                SharedConfig.MAX_PENDING_OUTBOUND_BYTES,
                SharedConfig.MAX_PENDING_OUTBOUND_PACKETS,
                sendableCap,
                true);
    }

    @Override
    public boolean send(byte[] data) {
        if (!client.isOpen()) {
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
    public int maxSupportedFrameSize() {
        return outboundBudget.maxPayloadBytes();
    }

    @Override
    public void close() {
        client.close();
    }

    private void failClose() {
        connection.closeConnection(CloseFrame.ABNORMAL_CLOSE, OUTPUT_FAILURE_REASON);
    }
}
