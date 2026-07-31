package io.lolyay.gma4j.net.client.net;

import io.lolyay.gma4j.net.client.ClientEventHandler;
import io.lolyay.gma4j.net.client.systemcodec.ClientDefaultSystemPacketCallback;
import io.lolyay.gma4j.net.client.transport.ServerConnection;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;
import io.lolyay.gma4j.net.codec.encryption.client.ClientEncryptionState;
import io.lolyay.gma4j.net.codec.encryption.client.IClientKnownCertificateKeeper;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packetdistributer.PacketDistributorImpl;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SKeepAlivePacket;
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

@RequiredArgsConstructor
@Getter
public class GMA4JNetClient {
    private final ClientEventHandler packetHandler;
    private final String claimedClientId;
    private final Map<GmaAuthType, GmaAuthClient> authClients;
    private final IClientKnownCertificateKeeper knownCertificateKeeper;
    private final CodecRegistry codecRegistry;
    private ClientEncryptionState clientEncryptionState;

    private ServerConnection serverConnection;
    private PacketPipeline pipeline;
    private IClientTransport transport;

    @Setter
    private UUID clientId;

    @Setter
    private volatile long lastPing = System.currentTimeMillis();

    @Getter(AccessLevel.NONE)
    private volatile boolean authenticated = false;
    @Getter(AccessLevel.NONE)
    private ScheduledExecutorService scheduler;
    @Getter(AccessLevel.NONE)
    private ScheduledFuture<?> handshakeTimeout;
    @Getter(AccessLevel.NONE)
    private ScheduledFuture<?> keepAliveTask;
    @Getter(AccessLevel.NONE)
    private long keepAliveId = 0;

    private void prepare(String uri) {
        IClientTransportFactory transportFactory = TransportManager.clientFactoryFor(URI.create(uri));
        this.clientEncryptionState = new ClientEncryptionState(knownCertificateKeeper);
        this.authenticated = false;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gma4j-client-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.pipeline = new PacketPipeline(
                new PacketDistributorImpl(
                        new ClientDefaultSystemPacketCallback(this),
                        packetHandler,() -> authenticated)
        );
        this.serverConnection = new ServerConnection(this, pipeline, packetHandler, claimedClientId, uri);

        transport = transportFactory.create(serverConnection);
        codecRegistry.warmup();
    }

    public void connect(String uri) {

        if(serverConnection != null && serverConnection.isConnected()) {
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

    // Re-Expose
    public boolean isConnected() {
        return serverConnection != null && serverConnection.isConnected();
    }

    public void disconnectWithError(Exception e) {
        disconnect();
        packetHandler.onConnectionError(e);
    }

    public <T extends GMAPacket<T>> void send(T packet) {
        serverConnection.send(packet);
    }
}
