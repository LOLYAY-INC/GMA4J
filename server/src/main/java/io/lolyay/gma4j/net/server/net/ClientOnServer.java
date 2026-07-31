package io.lolyay.gma4j.net.server.net;

import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;
import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.connection.server.ServerConnectionListener;
import io.lolyay.gma4j.net.codec.encryption.server.ServerEncryptionState;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketHandler;
import io.lolyay.gma4j.net.codec.packetdistributer.PacketDistributorImpl;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SHelloPacket;
import io.lolyay.gma4j.net.server.systemcodec.ServerDefaultSystemPacketCallback;
import io.lolyay.gma4j.net.shared.ENV;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.UUID;

@Slf4j
@Getter
public class ClientOnServer implements ServerConnectionListener, IPacketHandler {
    private final GMA4JNetServer netServer;
    private final String remoteId;
    private final ServerEncryptionState encryptionState;
    private final PacketPipeline pipeline;

    @Getter(AccessLevel.NONE)
    private MessageSender messageSender;
    private boolean connected = false;

    @Setter
    private UUID assignedId;
    @Setter
    private String claimedClientId;
    @Setter
    private boolean authenticated = false;
    @Setter
    private GmaAuthServer selectedAuthServer;
    @Setter
    private byte[] pendingChallenge;

    public ClientOnServer(GMA4JNetServer netServer, String remoteId) {
        this.netServer = netServer;
        this.remoteId = remoteId;
        this.encryptionState = new ServerEncryptionState(netServer.getCertificateProvider(), netServer::getSupportedAuthTypes);
        this.pipeline = new PacketPipeline(new PacketDistributorImpl(new ServerDefaultSystemPacketCallback(this), this));
    }

    @Override
    public <T extends GMAPacket<T>> boolean handle(T packet) {
        return netServer.getEventHandler().handle(this, packet);
    }

    public <T extends GMAPacket<T>> void send(T packet) {
        if(messageSender == null || !connected) {
            log.warn("Cannot send packet to {}, connection is not established", describe());
            return;
        }
        messageSender.send(pipeline.encode(packet));
    }

    public void disconnect(String reason) {
        if(!connected) {
            return;
        }
        connected = false;
        log.info("Dropping {} ({})", describe(), reason);
        netServer.removeClient(this);
        if(messageSender != null) {
            messageSender.close();
        }
    }

    @Override
    public void onConnectionEstablished(MessageSender sender) {
        this.messageSender = sender;
        this.connected = true;
        log.info("Client connected: {}", remoteId);
        netServer.getEventHandler().onClientConnected(this);
    }

    @Override
    public void onConnectionReceive(byte[] data) {
        pipeline.decodeAndPassDown(data);
    }

    @Override
    public void onConnectionClosed(String reason) {
        connected = false;
        netServer.removeClient(this);
        netServer.getEventHandler().onClientDisconnected(this, reason);
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
        if(!Arrays.equals(helloPacket.codecHash(), netServer.getCodecRegistry().getConfig().globalCodecState()))
            return "Codec hash mismatch: Client: " + Arrays.toString(helloPacket.codecHash()) + " != Our: " + Arrays.toString(netServer.getCodecRegistry().getConfig().globalCodecState());
        return null;
    }
}
