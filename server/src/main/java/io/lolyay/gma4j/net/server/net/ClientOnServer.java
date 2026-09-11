package io.lolyay.gma4j.net.server.net;

import io.lolyay.gma4j.net.codec.ClientType;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;
import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.connection.ConnectionSettings;
import io.lolyay.gma4j.net.codec.connection.server.ServerConnectionListener;
import io.lolyay.gma4j.net.codec.encryption.server.ServerEncryptionState;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketHandler;
import io.lolyay.gma4j.net.codec.packetdistributer.PacketDistributorImpl;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SHelloPacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CModeStatusPacket;
import io.lolyay.gma4j.net.server.systemcodec.ServerDefaultSystemPacketCallback;
import io.lolyay.gma4j.net.shared.ENV;
import io.lolyay.gma4j.net.shared.SharedConfig;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Getter
public class ClientOnServer implements ServerConnectionListener, IPacketHandler {
    private final GMA4JNetServer netServer;
    private final String remoteId;
    private final ServerEncryptionState encryptionState;
    private final PacketPipeline pipeline;
    private final ConnectionSettings settings;

    @Getter(AccessLevel.NONE)
    private volatile MessageSender messageSender;
    private volatile boolean connected = false;
    @Getter(AccessLevel.NONE)
    private final AtomicBoolean admitted = new AtomicBoolean();
    @Getter(AccessLevel.NONE)
    private volatile boolean everAdmitted;
    @Getter(AccessLevel.NONE)
    private volatile ScheduledFuture<?> handshakeDeadline;

    @Getter(AccessLevel.NONE)
    private long bigSizeReserved;
    @Getter(AccessLevel.NONE)
    private long lastModeChangeAt;
    @Getter(AccessLevel.NONE)
    private int modeChangeViolations;

    @Getter
    @Setter
    private ClientType clientType;
    @Setter
    private UUID assignedId;
    @Setter
    private String claimedClientId;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile boolean authenticated = false;
    @Setter
    private GmaAuthServer selectedAuthServer;
    @Setter
    private byte[] pendingChallenge;

    public ClientOnServer(GMA4JNetServer netServer, String remoteId) {
        this.netServer = netServer;
        this.remoteId = remoteId;
        this.encryptionState = new ServerEncryptionState(netServer.getCertificateProvider(), netServer::getSupportedAuthTypes);
        this.settings = new ConnectionSettings();
        this.pipeline = new PacketPipeline(
                this::disconnect,
                new PacketDistributorImpl(new ServerDefaultSystemPacketCallback(this),
                        this, () -> authenticated),
                settings,
                () -> authenticated
        );
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
        if (authenticated) {
            cancelHandshakeDeadline();
        }
    }

    private void cancelHandshakeDeadline() {
        ScheduledFuture<?> deadline = handshakeDeadline;
        if (deadline != null) {
            deadline.cancel(false);
            handshakeDeadline = null;
        }
    }

    @Override
    public <T extends GMAPacket<T>> boolean handle(T packet) {
        return netServer.getEventHandler().handle(this, packet);
    }

    public synchronized <T extends GMAPacket<T>> void send(T packet) {
        send(packet, false);
    }

    public synchronized <T extends GMAPacket<T>> void send(T packet, boolean urgent) {
        if(messageSender == null || !connected) {
            log.warn("Cannot send packet to {}, connection is not established", describe());
            return;
        }
        boolean expedite = urgent && settings.isLowLatency();
        messageSender.send(pipeline.encode(packet, expedite), expedite);
    }

    public synchronized <T extends GMAPacket<T>> CompletableFuture<Void> sendWithCompletion(T packet) {
        return sendWithCompletion(packet, false);
    }

    public synchronized <T extends GMAPacket<T>> CompletableFuture<Void> sendWithCompletion(T packet, boolean urgent) {
        if(messageSender == null || !connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("Connection is not established"));
        }
        boolean expedite = urgent && settings.isLowLatency();
        try {
            return messageSender.sendWithCompletion(pipeline.encode(packet, expedite), expedite);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Status goes out under the old settings, then the new ones apply */
    public synchronized void setModes(boolean lowLatency, boolean bigSize, int requestedMaxPacketSize) {
        if (!connected) {
            return;
        }
        boolean grantLowLatency = lowLatency && SharedConfig.ALLOW_LOW_LATENCY_MODE;
        boolean grantBig = bigSize && SharedConfig.ALLOW_BIG_SIZE_MODE;
        int granted = settings.getBasePacketSize();
        if (grantBig) {
            granted = grantBigSize(requestedMaxPacketSize);
            grantBig = granted > settings.getBasePacketSize();
        }
        send(new S2CModeStatusPacket(grantLowLatency, grantBig, granted));
        settings.apply(grantLowLatency, grantBig, granted);
        MessageSender sender = messageSender;
        if (sender != null) {
            sender.applyModes(grantLowLatency, grantBig);
        }
        log.info("Modes for {}: lowLatency={}, bigSize={}, maxPacketSize={}", describe(), grantLowLatency, grantBig, granted);
        netServer.getEventHandler().onClientModeChanged(this);
    }

    /** Reservations are kept until disconnect since receive limits never shrink */
    private int grantBigSize(int requested) {
        int base = settings.getBasePacketSize();
        MessageSender sender = messageSender;
        long ceiling = Math.min(SharedConfig.MAX_BIG_PACKET_SIZE,
                sender == null ? base : sender.maxSupportedFrameSize());
        long wanted = Math.min(Math.max(requested, base), ceiling);
        long delta = (wanted - base) - bigSizeReserved;
        if (delta > 0) {
            bigSizeReserved += netServer.reserveBigSizeBudget(delta);
        }
        // the reservation is monotonic for receive capacity, the grant is not
        return (int) Math.min(wanted, base + bigSizeReserved);
    }

    public synchronized boolean modeChangeAllowed() {
        long now = System.currentTimeMillis();
        if (now - lastModeChangeAt < SharedConfig.MODE_CHANGE_MIN_INTERVAL_MS) {
            if (++modeChangeViolations >= SharedConfig.MAX_MODE_CHANGE_VIOLATIONS) {
                disconnect("Mode change flood");
            }
            return false;
        }
        lastModeChangeAt = now;
        modeChangeViolations = 0;
        return true;
    }

    @Override
    public int maxIncomingFrameSize() {
        return settings.receiveAllowance();
    }

    public void disconnect(String reason) {
        if(!connected) {
            return;
        }
        log.info("Dropping {} ({})", describe(), reason);
        close();
    }

    public void disconnect() {
        if(!connected) {
            return;
        }
        log.info("Dropping {}.", describe());
        close();

    }

    @Override
    public void onConnectionEstablished(MessageSender sender) {
        if (!netServer.tryAdmit()) {
            log.warn("Rejecting {}: connection limit of {} reached", remoteId, SharedConfig.MAX_CONNECTIONS);
            sender.close();
            return;
        }
        admitted.set(true);
        everAdmitted = true;
        this.messageSender = sender;
        this.connected = true;
        handshakeDeadline = netServer.scheduleHandshakeDeadline(() -> {
            if (!authenticated) {
                disconnect("Auth handshake timeout");
            }
        });
        log.info("Client connected: {}", remoteId);
        netServer.getEventHandler().onClientConnected(this);
    }

    @Override
    public void onConnectionReceive(byte[] data) {
        pipeline.decodeAndPassDown(data);
    }

    @Override
    public void onConnectionClosed(String reason) {
        close();
        // rejected connections never reached the event handler
        if (everAdmitted) {
            netServer.getEventHandler().onClientDisconnected(this, reason);
        }
    }

    @Override
    public void onConnectionError(Throwable e) {
        log.error("Error on connection {}", describe(), e);
        netServer.getEventHandler().onClientError(this, e);
    }

    private String describe() {
        return claimedClientId != null ? claimedClientId + "/" + assignedId : remoteId;
    }

    public String verifyCompatibility(C2SHelloPacket helloPacket) {
        if(helloPacket.sysCodecVersion() != ENV.SYSTEM_CODEC_VERSION)
            return "System codec version mismatch: Client: " + helloPacket.sysCodecVersion() + " != Our: " + ENV.SYSTEM_CODEC_VERSION;
        if(helloPacket.encVersion() != ENV.ENCRYPTION_CODEC_VERSION)
            return "Encryption codec version mismatch: Client: " + helloPacket.encVersion() + " != Our: " + ENV.ENCRYPTION_CODEC_VERSION;
        if(helloPacket.gma4jVersion() != ENV.GMA4J_VERSION)
            log.warn("Client {} is using a different version of GMA4J, please update!", remoteId);
        if(helloPacket.clientType() == ClientType.GMA4J_JAVA && !Arrays.equals(helloPacket.codecHash(), netServer.getCodecRegistry().getConfig().globalCodecState()))
            return "Codec hash mismatch: Client: " + Arrays.toString(helloPacket.codecHash()) + " != Our: " + Arrays.toString(netServer.getCodecRegistry().getConfig().globalCodecState());
        return null;
    }

    private void close() {
        connected = false;
        cancelHandshakeDeadline();
        if(messageSender != null) {
            messageSender.close();
        }
        pipeline.close();

        synchronized (this) {
            if (bigSizeReserved > 0) {
                netServer.releaseBigSizeBudget(bigSizeReserved);
                bigSizeReserved = 0;
            }
        }
        if (admitted.compareAndSet(true, false)) {
            netServer.releaseAdmission();
        }

        try {
            netServer.removeClient(this);
        } catch (Exception ignored) {}
    }
}
