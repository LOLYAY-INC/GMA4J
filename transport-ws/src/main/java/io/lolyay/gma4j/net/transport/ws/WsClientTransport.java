package io.lolyay.gma4j.net.transport.ws;

import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.transport.IClientTransport;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.protocols.Protocol;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;

public class WsClientTransport implements IClientTransport {

    private final ClientConnectionListener listener;
    private volatile WebSocketClient client;

    public WsClientTransport(ClientConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public void connect(URI uri) {
        Draft_6455 draft = new Draft_6455(
                Collections.emptyList(),
                Collections.singletonList(new Protocol("")),
                SharedConfig.MAX_PACKET_SIZE
        );


        client = new WebSocketClient(uri,draft) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                listener.onConnectionEstablished(new WsClientConnection(this));
            }

            @Override
            public void onMessage(String message) {
            }

            @Override
            public void onMessage(ByteBuffer bytes) {
                if(bytes.remaining() > SharedConfig.MAX_PACKET_SIZE) {
                    PacketCodingException error = new PacketCodingException(
                            "Packet too large: " + bytes.remaining() + " > " + SharedConfig.MAX_PACKET_SIZE);
                    listener.onConnectionError(error);
                    close(CloseFrame.TOOBIG, "Packet too large");
                    return;
                }

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
