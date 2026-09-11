package io.lolyay.gma4j.net.transport.ws;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import org.java_websocket.client.WebSocketClient;

public class WsClientConnection implements MessageSender {

    private final WebSocketClient client;
    private final int frameCap;

    public WsClientConnection(WebSocketClient client, int frameCap) {
        this.client = client;
        this.frameCap = frameCap;
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
    public int maxSupportedFrameSize() {
        // Draft frame cap is fixed at construction, so big size cannot grow past it
        return frameCap;
    }

    @Override
    public void close() {
        client.close();
    }
}
