package io.lolyay.gma4j.net.transport.ws;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import org.java_websocket.client.WebSocketClient;

public class WsClientConnection implements MessageSender {

    private final WebSocketClient client;

    public WsClientConnection(WebSocketClient client) {
        this.client = client;
    }

    @Override
    public boolean send(byte[] data) {
        if (!client.isOpen()) {
            return false;
        }
        client.send(data);
        return true;
    }

    @Override
    public void close() {
        client.close();
    }
}
