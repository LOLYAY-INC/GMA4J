package io.lolyay.gma4j.net.transport.ws;

import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;
import io.lolyay.gma4j.net.transport.IClientTransport;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;

public class WsClientTransport implements IClientTransport {

    private final ClientConnectionListener listener;
    private volatile WebSocketClient client;

    public WsClientTransport(ClientConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public void connect(URI uri) {
        client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                listener.onConnectionEstablished(new WsClientConnection(this));
            }

            @Override
            public void onMessage(String message) {
            }

            @Override
            public void onMessage(ByteBuffer bytes) {
                byte[] data = new byte[bytes.remaining()];
                bytes.get(data);
                listener.onConnectionReceive(data);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                listener.onConnectionClosed(reason);
            }

            @Override
            public void onError(Exception ex) {
                listener.onConnectionError(ex);
            }
        };
        client.connect();
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
