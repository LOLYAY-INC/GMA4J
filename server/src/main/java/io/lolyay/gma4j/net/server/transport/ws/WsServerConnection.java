package io.lolyay.gma4j.net.server.transport.ws;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import org.java_websocket.WebSocket;

public class WsServerConnection implements MessageSender {

    private final WebSocket connection;

    public WsServerConnection(WebSocket connection) {
        this.connection = connection;
    }

    @Override
    public boolean send(byte[] data) {
        if (!connection.isOpen()) {
            return false;
        }
        connection.send(data);
        return true;
    }

    @Override
    public void close() {
        connection.close();
    }
}
