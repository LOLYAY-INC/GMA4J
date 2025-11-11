package io.lolyay.gma4j.net.client;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Simple Jetty WebSocket client.
 */
public class WebSocketClient {
    private final org.eclipse.jetty.websocket.client.WebSocketClient client;
    private final ClientPacketSocket packetSocket;
    private final ClientWebSocketHandler handler;

    public WebSocketClient(ClientWebSocketHandler.PacketHandler packetHandler) {
        this.client = new org.eclipse.jetty.websocket.client.WebSocketClient();
        this.packetSocket = new ClientPacketSocket();
        this.handler = new ClientWebSocketHandler(packetSocket, packetHandler);
    }

    public void connect(String uri) throws Exception {
        client.start();
        client.connect(handler, new URI(uri)).get(10, TimeUnit.SECONDS);
        System.out.println("Connecting to: " + uri);
    }

    public ClientPacketSocket getPacketSocket() {
        return packetSocket;
    }

    public void stop() throws Exception {
        client.stop();
    }
}
