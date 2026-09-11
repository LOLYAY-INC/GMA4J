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
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Getter
public class ClientOnServer implements ServerConnectionListener, IPacketHandler {
    private final GMA4JNetServer netServer;
    private final String remoteId;
    private final ServerEncryptionState encryptionState;
    private final PacketPipeline pipeline;
    private final ConnectionSettings settings;

    @Getter(AccessLevel.NONE)
    private final Object lifecycleMonitor = new Object();
    @Getter(AccessLevel.NONE)
    private volatile MessageSender messageSender;
    @Getter(AccessLevel.NONE)
    private ScheduledFuture<?> handshakeDeadline;
    private volatile boolean connected = false;
    private volatile boolean authenticated = false;
    @Getter(AccessLevel.NONE)
    private volatile boolean closed = false;
    @Getter(AccessLevel.NONE)
    private boolean disconnectedNotified = false;

    @Getter(AccessLevel.NONE)
    private long bigSizeReserved;
    @Getter(AccessLevel.NONE)
    private long lastModeChangeAt;
    @Getter(AccessLevel.NONE)
    private int modeChangeViolations;

    @Getter
    @Setter
    private volatile ClientType clientType;
    @Setter
    private volatile UUID assignedId;
    @Setter
    private volatile String claimedClientId;
    @Setter
    private volatile GmaAuthServer selectedAuthServer;
    @Setter
    private volatile byte[] pendingChallenge;

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

    @Override
    public <T extends GMAPacket<T>> boolean handle(T packet) {
        return netServer.getEventHandler().handle(this, packet);
    }

    public synchronized <T extends GMAPacket<T>> void send(T packet) {
        send(packet, false);
    }

    public synchronized <T extends GMAPacket<T>> void send(T packet, boolean urgent) {
        MessageSender sender = messageSender;
        if(sender == null || !connected) {
            log.warn("Cannot send packet to {}, connection is not established", describe());
            return;
        }
        boolean expedite = urgent && settings.isLowLatency();
        byte[] encoded = pipeline.encode(packet, expedite);
        try {
            if(!sender.send(encoded, expedite)) {
                log.warn("Sender rejected packet for {}", describe());
                disconnect("Packet send rejected");
            }
        } catch (RuntimeException e) {
            log.warn("Sender failed for {}", describe(), e);
            disconnect("Packet send failed");
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
        if (!connected) {
            return;
        }
        settings.apply(grantLowLatency, grantBig, granted);
        MessageSender sender = messageSender;
        if (sender != null) {
            sender.applyModes(grantLowLatency, grantBig);
        }
        log.info("Modes for {}: lowLatency={}, bigSize={}, maxPacketSize={}", describe(), grantLowLatency, grantBig, granted);
        netServer.getEventHandler().onClientModeChanged(this);
    }

    /** Reservations last until disconnect since receive limits never shrink. */
    private int grantBigSize(int requested) {
        synchronized (lifecycleMonitor) {
            int base = settings.getBasePacketSize();
            if (closed) {
                return base;
            }
            MessageSender sender = messageSender;
            long ceiling = Math.min(SharedConfig.MAX_BIG_PACKET_SIZE,
                    sender == null ? base : sender.maxSupportedFrameSize());
            long wanted = Math.min(Math.max(requested, base), ceiling);
            long delta = (wanted - base) - bigSizeReserved;
            if (delta > 0) {
                bigSizeReserved += netServer.reserveBigSizeBudget(delta);
            }
            return (int) Math.min(wanted, base + bigSizeReserved);
        }
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
        notifyDisconnected(reason);
    }

    public void disconnect() {
        if(!connected) {
            return;
        }
        log.info("Dropping {}.", describe());
        close();
        notifyDisconnected("Disconnected");
    }

    @Override
    public void onConnectionEstablished(MessageSender sender) {
        boolean rejected;
        synchronized (lifecycleMonitor) {
            rejected = closed || connected;
            if(!rejected) {
                messageSender = sender;
                connected = true;
            }
        }
        if(rejected) {
            sender.close();
            return;
        }

        log.info("Client connected: {}", remoteId);
        netServer.trackConnection(this);
        if(connected) {
            netServer.getEventHandler().onClientConnected(this);
        }
    }

    @Override
    public void onConnectionReceive(byte[] data) {
        if(connected) {
            pipeline.decodeAndPassDown(data);
        }
    }

    @Override
    public void onConnectionClosed(String reason) {
        close();
        notifyDisconnected(reason);
    }

    @Override
    public void onConnectionError(Throwable e) {
        log.error("Error on connection {}", describe(), e);
        close();
        notifyDisconnected("Connection error");
        netServer.getEventHandler().onClientError(this, e);
    }

    public void setAuthenticated(boolean authenticated) {
        ScheduledFuture<?> deadline = null;
        synchronized (lifecycleMonitor) {
            if(closed && authenticated) {
                return;
            }
            this.authenticated = authenticated;
            if(authenticated) {
                deadline = takeHandshakeDeadline();
            }
        }
        cancelDeadline(deadline);
    }

    public boolean completeAuthentication() {
        ScheduledFuture<?> deadline;
        synchronized (lifecycleMonitor) {
            if(closed || !connected || authenticated) {
                return false;
            }
            if(netServer.registerClient(this) == null) {
                return false;
            }
            authenticated = true;
            deadline = takeHandshakeDeadline();
        }
        cancelDeadline(deadline);
        return true;
    }

    public boolean acceptPendingChallenge(byte[] challenge) {
        synchronized (lifecycleMonitor) {
            if(closed || !connected || authenticated) {
                return false;
            }
            pendingChallenge = challenge;
            return true;
        }
    }

    void installHandshakeDeadline(ScheduledFuture<?> deadline) {
        ScheduledFuture<?> deadlineToCancel = null;
        synchronized (lifecycleMonitor) {
            if(closed || !connected || authenticated) {
                deadlineToCancel = deadline;
            } else {
                deadlineToCancel = handshakeDeadline;
                handshakeDeadline = deadline;
            }
        }
        cancelDeadline(deadlineToCancel);
    }

    void expireHandshake() {
        CloseState closeState;
        synchronized (lifecycleMonitor) {
            if(authenticated || !connected || closed) {
                return;
            }
            closeState = beginClose();
        }
        log.warn("Authentication handshake timed out for {} after {}ms", describe(), SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS);
        finishClose(closeState);
        notifyDisconnected("Authentication handshake timed out");
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

    private String describe() {
        return claimedClientId != null ? claimedClientId + "/" + assignedId : remoteId;
    }

    private void close() {
        CloseState closeState;
        synchronized (lifecycleMonitor) {
            if(closed) {
                return;
            }
            closeState = beginClose();
        }
        finishClose(closeState);
    }

    private CloseState beginClose() {
        closed = true;
        connected = false;
        authenticated = false;
        MessageSender sender = messageSender;
        messageSender = null;
        long reserved = bigSizeReserved;
        bigSizeReserved = 0;
        return new CloseState(sender, takeHandshakeDeadline(), reserved);
    }

    private void finishClose(CloseState closeState) {
        cancelDeadline(closeState.deadline());
        netServer.untrackConnection(this);
        netServer.removeClient(this);
        netServer.releaseBigSizeBudget(closeState.bigSizeReserved());
        try {
            if(closeState.sender() != null) {
                closeState.sender().close();
            }
        } finally {
            pipeline.requestClose();
        }
    }

    private ScheduledFuture<?> takeHandshakeDeadline() {
        ScheduledFuture<?> deadline = handshakeDeadline;
        handshakeDeadline = null;
        return deadline;
    }

    private void notifyDisconnected(String reason) {
        synchronized (lifecycleMonitor) {
            if(disconnectedNotified) {
                return;
            }
            disconnectedNotified = true;
        }
        netServer.getEventHandler().onClientDisconnected(this, reason);
    }

    private static void cancelDeadline(ScheduledFuture<?> deadline) {
        if(deadline != null) {
            deadline.cancel(false);
        }
    }

    private record CloseState(MessageSender sender, ScheduledFuture<?> deadline, long bigSizeReserved) {
    }
}
