package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.Packet;
import io.lolyay.gma4j.packets.system.PacketPong;
import io.lolyay.gma4j.packets.system.PacketVersion;

import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced WebSocket client with auto-reconnect, ping/pong, and advanced features.
 */
public class GMA4JWebSocketClient {
    private final GMA4JClientSettings settings;
    private final EnhancedPacketHandler userHandler;
    private final ClientPacketSocket packetSocket;
    private final LatencyMonitor latencyMonitor;
    private final ScheduledExecutorService scheduler;
    
    private String currentUri;
    private EnhancedWebSocketHandler currentHandler;
    private AtomicBoolean isConnected = new AtomicBoolean(false);
    private AtomicBoolean shouldReconnect = new AtomicBoolean(false);
    private AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private ScheduledFuture<?> pingTask;
    private ScheduledFuture<?> reconnectTask;

    public GMA4JWebSocketClient(GMA4JClientSettings settings, EnhancedPacketHandler userHandler) {
        this.settings = settings;
        this.userHandler = userHandler;
        this.packetSocket = new ClientPacketSocket();
        this.latencyMonitor = new LatencyMonitor(packetSocket);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "GMA4J-Client-Scheduler");
            t.setDaemon(true);
            return t;
        });

        // Configure compression in socket
        packetSocket.setCompressionThreshold(settings.getCompressionThreshold());
    }

    /**
     * Connect to the WebSocket server.
     */
    public void connect(String uri) throws Exception {
        this.currentUri = uri;
        shouldReconnect.set(settings.isAutoReconnect());

        doConnect();
    }

    private void doConnect() throws Exception {
        System.out.println("[Client] Connecting to: " + currentUri);

        EnhancedWebSocketHandler handler = new EnhancedWebSocketHandler(
            new URI(currentUri),
            packetSocket,
            settings,
            latencyMonitor,
            new InternalPacketHandler()
        );

        currentHandler = handler;

        try {
            boolean connected = handler.connectBlocking(
                settings.getConnectionTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );

            if (!connected) {
                handler.close();
                currentHandler = null;
                packetSocket.clearConnection();
                throw new TimeoutException("Connection timeout after " + settings.getConnectionTimeout());
            }
        } catch (InterruptedException e) {
            handler.close();
            currentHandler = null;
            packetSocket.clearConnection();
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException e) {
            handler.close();
            currentHandler = null;
            packetSocket.clearConnection();
            throw e;
        }
    }

    /**
     * Disconnect from the server.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        stopPingTask();
        stopReconnectTask();
        
        if (currentHandler != null) {
            currentHandler.close();
            currentHandler = null;
        }

        packetSocket.clearConnection();
        isConnected.set(false);
    }

    /**
     * Stop the client completely.
     */
    public void stop() throws Exception {
        disconnect();
        scheduler.shutdown();
    }

    private void startPingTask() {
        if (!settings.isEnablePing() || pingTask != null) {
            return;
        }

        long intervalMs = settings.getPingInterval().toMillis();
        pingTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                latencyMonitor.sendPing();
            } catch (Exception e) {
                System.err.println("[Client] Ping failed: " + e.getMessage());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopPingTask() {
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
    }

    private void scheduleReconnect() {
        if (!shouldReconnect.get() || reconnectTask != null) {
            return;
        }

        int attempts = reconnectAttempts.get();
        if (settings.getMaxReconnectAttempts() >= 0 && attempts >= settings.getMaxReconnectAttempts()) {
            System.err.println("[Client] Max reconnect attempts reached (" + attempts + ")");
            userHandler.onReconnectFailed(this);
            return;
        }

        long delayMs = settings.getReconnectDelay().toMillis();
        System.out.println("[Client] Reconnecting in " + delayMs + "ms (attempt " + (attempts + 1) + ")");

        reconnectTask = scheduler.schedule(() -> {
            reconnectTask = null;
            reconnectAttempts.incrementAndGet();
            
            try {
                doConnect();
            } catch (Exception e) {
                System.err.println("[Client] Reconnect failed: " + e.getMessage());
                scheduleReconnect(); // Try again
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void stopReconnectTask() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    public ClientPacketSocket getPacketSocket() {
        return packetSocket;
    }

    public LatencyMonitor getLatencyMonitor() {
        return latencyMonitor;
    }

    public GMA4JClientSettings getSettings() {
        return settings;
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    /**
     * Internal handler that manages system packets and delegates user packets.
     */
    private class InternalPacketHandler implements ClientWebSocketHandler.PacketHandler {
        
        @Override
        public void onConnect(ClientPacketSocket socket) {
            isConnected.set(true);
            reconnectAttempts.set(0);
            stopReconnectTask();
            latencyMonitor.reset();

            System.out.println("[Client] Connected successfully");

            // Send version exchange
            socket.sendPacket(new PacketVersion(
                settings.getProtocolVersion(),
                settings.getClientName(),
                settings.getClientVersion()
            ));

            // Start ping task
            startPingTask();

            // Notify user handler
            userHandler.onConnect(socket);
        }

        @Override
        public void onPacket(ClientPacketSocket socket, Packet packet) {
            // Handle system packets
            if (packet instanceof PacketPong pong) {
                latencyMonitor.handlePong(pong);
                return;
            } else if (packet instanceof PacketVersion version) {
                System.out.println("[Client] Server version: " + version);
                userHandler.onVersionExchange(socket, version);
                return;
            }

            // Delegate to user handler
            userHandler.onPacket(socket, packet);
        }

        @Override
        public void onDisconnect(ClientPacketSocket socket) {
            isConnected.set(false);
            stopPingTask();

            System.out.println("[Client] Disconnected");
            userHandler.onDisconnect(socket);

            // Auto-reconnect if enabled
            if (shouldReconnect.get()) {
                scheduleReconnect();
            }
        }
    }

    /**
     * Enhanced packet handler interface with additional callbacks.
     */
    public interface EnhancedPacketHandler {
        void onConnect(ClientPacketSocket socket);
        void onPacket(ClientPacketSocket socket, Packet packet);
        void onDisconnect(ClientPacketSocket socket);
        
        /**
         * Called when server version is received.
         */
        default void onVersionExchange(ClientPacketSocket socket, PacketVersion serverVersion) {
            // Override to handle version compatibility
        }
        
        /**
         * Called when all reconnect attempts have failed.
         */
        default void onReconnectFailed(GMA4JWebSocketClient client) {
            System.err.println("[Client] All reconnection attempts failed");
        }
    }
}
