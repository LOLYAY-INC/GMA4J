package io.lolyay.gma4j.net.client;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Simple Java-WebSocket client wrapper.
 */
public class WebSocketClient {
    private final ClientPacketSocket packetSocket;
    private final ClientWebSocketHandler.PacketHandler packetHandler;
    private ClientWebSocketHandler handler;

    public WebSocketClient(ClientWebSocketHandler.PacketHandler packetHandler) {
        this.packetSocket = new ClientPacketSocket();
        this.packetHandler = packetHandler;
    }

    public void connect(String uri) throws Exception {
        handler = new ClientWebSocketHandler(new URI(uri), packetSocket, packetHandler);
        System.out.println("Connecting to: " + uri);

        try {
            boolean connected = handler.connectBlocking(10, TimeUnit.SECONDS);
            if (!connected) {
                handler.close();
                handler = null;
                packetSocket.clearConnection();
                throw new TimeoutException("Connection timeout after 10 seconds");
            }
        } catch (InterruptedException e) {
            handler.close();
            handler = null;
            packetSocket.clearConnection();
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException e) {
            handler.close();
            handler = null;
            packetSocket.clearConnection();
            throw e;
        }
    }

    public ClientPacketSocket getPacketSocket() {
        return packetSocket;
    }

    public void stop() {
        if (handler != null) {
            handler.close();
            handler = null;
        }
        packetSocket.clearConnection();
    }
}
