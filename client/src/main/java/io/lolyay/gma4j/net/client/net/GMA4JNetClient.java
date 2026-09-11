package io.lolyay.gma4j.net.client.net;

import io.lolyay.gma4j.net.client.ClientEventHandler;
import io.lolyay.gma4j.net.client.GMA4JClient;
import io.lolyay.gma4j.net.client.systemcodec.ClientDefaultSystemPacketCallback;
import io.lolyay.gma4j.net.client.transport.ServerConnection;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;
import io.lolyay.gma4j.net.codec.connection.ConnectionSettings;
import io.lolyay.gma4j.net.codec.encryption.client.ClientEncryptionState;
import io.lolyay.gma4j.net.codec.encryption.client.IClientKnownCertificateKeeper;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packetdistributer.PacketDistributorImpl;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SKeepAlivePacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SModeRequestPacket;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.transport.IClientTransport;
import io.lolyay.gma4j.net.transport.IClientTransportFactory;
import io.lolyay.gma4j.net.transport.TransportManager;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
@Getter
public class GMA4JNetClient {
    private final ClientEventHandler packetHandler;
    private final String claimedClientId;
    private final Map<GmaAuthType, GmaAuthClient> authClients;
    private final IClientKnownCertificateKeeper knownCertificateKeeper;
    private final CodecRegistry codecRegistry;
    private final GMA4JClient parent;
    private ClientEncryptionState clientEncryptionState;

    private ServerConnection serverConnection;
    private PacketPipeline pipeline;
    private IClientTransport transport;
    private ConnectionSettings connectionSettings;

    @Setter
    private UUID clientId;

    @Setter
    private volatile long lastPing = System.currentTimeMillis();

    @Getter
    private volatile boolean authenticated = false;
    @Getter(AccessLevel.NONE)
    private ScheduledExecutorService scheduler;
    @Getter(AccessLevel.NONE)
    private ScheduledFuture<?> handshakeTimeout;
    @Getter(AccessLevel.NONE)
    private ScheduledFuture<?> keepAliveTask;
    @Getter(AccessLevel.NONE)
    private long keepAliveId = 0;
    @Getter(AccessLevel.NONE)
    private volatile AtomicBoolean sessionClosed;

    private void prepare(String uri) {
        IClientTransportFactory transportFactory = TransportManager.clientFactoryFor(URI.create(uri));
        this.clientEncryptionState = new ClientEncryptionState(knownCertificateKeeper);
        this.authenticated = false;
        this.sessionClosed = new AtomicBoolean(false);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gma4j-client-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.connectionSettings = new ConnectionSettings();
        this.pipeline = new PacketPipeline(
                this::disconnect,
                new PacketDistributorImpl(
                        new ClientDefaultSystemPacketCallback(this),
                        packetHandler,() -> authenticated),
                connectionSettings,
                () -> authenticated
        );
        this.serverConnection = new ServerConnection(this, pipeline, connectionSettings, packetHandler, claimedClientId, uri);

        transport = transportFactory.create(serverConnection);
        codecRegistry.warmup();
    }

    public void connect(String uri) {
        // dispose whatever a previous connect left behind, timers included
        if(sessionClosed != null) {
            disconnect();
        }
        prepare(uri);

        if(transport == null) {
            throw new IllegalStateException("Error in preparing Connection to " + uri + " (Transport is null)");
        }

        transport.connect(URI.create(uri));
    }

    public void onConnectionReady() {
        lastPing = System.currentTimeMillis();
        handshakeTimeout = scheduler.schedule(() -> {
            if(!authenticated) {
                disconnectWithError(new TimeoutException("Auth handshake did not complete within " + SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS + "ms"));
            }
        }, SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public void onAuthenticated() {
        authenticated = true;
        lastPing = System.currentTimeMillis();
        if(handshakeTimeout != null) {
            handshakeTimeout.cancel(false);
        }
        keepAliveTask = scheduler.scheduleAtFixedRate(this::keepAliveTick,
                SharedConfig.KEEPALIVE_INTERVAL_MS, SharedConfig.KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void onKeepAlive() {
        lastPing = System.currentTimeMillis();
    }

    private void keepAliveTick() {
        if(!isConnected()) {
            return;
        }
        if(System.currentTimeMillis() - lastPing > SharedConfig.KEEPALIVE_TIMEOUT_MS) {
            disconnectWithError(new TimeoutException("Keepalive timed out after " + SharedConfig.KEEPALIVE_TIMEOUT_MS + "ms"));
            return;
        }
        send(new C2SKeepAlivePacket(keepAliveId++));
    }

    public void disconnect() {
        AtomicBoolean closed = sessionClosed;
        if(closed != null && !closed.compareAndSet(false, true)) {
            return;
        }
        if(handshakeTimeout != null) {
            handshakeTimeout.cancel(false);
        }
        if(keepAliveTask != null) {
            keepAliveTask.cancel(false);
        }
        if(scheduler != null) {
            scheduler.shutdownNow();
        }
        if(transport != null) {
            transport.close();
        }
        if(pipeline != null) {
            pipeline.close();
        }
    }

    /** Remote closes and transport errors must also stop timers and the scheduler */
    public void onRemoteDisconnect() {
        disconnect();
    }

    // Re-Expose
    public boolean isConnected() {
        return serverConnection != null && serverConnection.isConnected();
    }

    public void disconnectWithError(Exception e) {
        AtomicBoolean closed = sessionClosed;
        boolean wasOpen = closed == null || !closed.get();
        disconnect();
        if(wasOpen) {
            packetHandler.onConnectionError(e);
        }
    }

    public <T extends GMAPacket<T>> void send(T packet) {
        serverConnection.send(packet);
    }

    public <T extends GMAPacket<T>> void sendUrgent(T packet) {
        serverConnection.send(packet, true);
    }

    public <T extends GMAPacket<T>> CompletableFuture<Void> sendWithCompletion(T packet) {
        return serverConnection.sendWithCompletion(packet, false);
    }

    public <T extends GMAPacket<T>> CompletableFuture<Void> sendWithCompletion(T packet, boolean urgent) {
        return serverConnection.sendWithCompletion(packet, urgent);
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void requestModes(boolean lowLatency, boolean bigSize, int requestedMaxPacketSize) {
        if(!authenticated) {
            throw new IllegalStateException("Modes can only be requested after authentication");
        }
        if(requestedMaxPacketSize < 0) {
            throw new IllegalArgumentException("requestedMaxPacketSize must be >= 0");
        }
        if(requestedMaxPacketSize > SharedConfig.MAX_BIG_PACKET_SIZE) {
            throw new IllegalArgumentException("requestedMaxPacketSize exceeds MAX_BIG_PACKET_SIZE");
        }
        if(bigSize) {
            // raise the decoder allowance now so a grant in the same read is not cut off
            connectionSettings.raiseReceiveAllowance(requestedMaxPacketSize);
        }
        send(new C2SModeRequestPacket(lowLatency, bigSize, requestedMaxPacketSize));
    }

    /** Called on S2CModeStatusPacket, the grant is already validated */
    public void applyModes(boolean lowLatency, boolean bigSize, int maxPacketSize) {
        connectionSettings.apply(lowLatency, bigSize, maxPacketSize);
        serverConnection.applyModes(lowLatency, bigSize);
        packetHandler.onModesChanged(lowLatency, bigSize, maxPacketSize);
    }
}
