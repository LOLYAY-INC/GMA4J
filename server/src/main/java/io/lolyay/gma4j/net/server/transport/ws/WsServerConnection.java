package io.lolyay.gma4j.net.server.transport.ws;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import org.java_websocket.WebSocket;

public class WsServerConnection implements MessageSender {

    private final WebSocket connection;
    private final int frameCap;

    public WsServerConnection(WebSocket connection, int frameCap) {
        this.connection = connection;
        this.frameCap = frameCap;
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
    public int maxSupportedFrameSize() {
        // Draft frame cap is fixed at construction, so big size cannot grow past it
        return frameCap;
    }

    @Override
    public void close() {
        connection.close();
    }
}
