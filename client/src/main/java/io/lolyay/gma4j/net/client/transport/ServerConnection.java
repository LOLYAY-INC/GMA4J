package io.lolyay.gma4j.net.client.transport;

import io.lolyay.gma4j.net.client.net.GMA4JNetClient;
import io.lolyay.gma4j.net.codec.ClientType;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;
import io.lolyay.gma4j.net.codec.connection.IConnectionStateCallback;
import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;
import io.lolyay.gma4j.net.codec.encryption.client.ClientEncryptionState;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SHelloPacket;
import io.lolyay.gma4j.net.shared.ENV;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ServerConnection implements ClientConnectionListener { // client has a connection to a server
    private final GMA4JNetClient netClient;
    private final PacketPipeline pipeline;
    private final IConnectionStateCallback connectionStateCallback;

    private final String clientClaimedStringId;
    private final String uri;

    private MessageSender messageSender;

    @Getter
    private boolean isConnected = false;

    public <T extends GMAPacket<T>> void send(T data) {
        if(messageSender == null || !isConnected) {
            log.warn("Cannot send packet, connection is not established");
            return;
        }
        byte[] packet = pipeline.encode(data);
        messageSender.send(packet);
    }

    @Override
    public void onConnectionEstablished(MessageSender sender) {
        this.messageSender = sender;
        isConnected = true;
        connectionStateCallback.onConnectionEstablished();
        log.info("Connection established with {}", uri);


        // Encryption
        ClientEncryptionState.ClientEncryptionStateInfo stateInfo = netClient.getClientEncryptionState().initConnection(uri);

        C2SHelloPacket helloPacket = new C2SHelloPacket(
                ENV.GMA4J_VERSION,
                uri,
                ClientType.GMA4J_JAVA,
                ENV.SYSTEM_CODEC_VERSION,
                ENV.ENCRYPTION_CODEC_VERSION,
                netClient.getCodecRegistry().getConfig().globalCodecState(),
                netClient.getCodecRegistry().getConfig().systemCodecVersion(),
                netClient.getCodecRegistry().getConfig().codecState().size(),
                netClient.getClientEncryptionState().supportedModes(),
                stateInfo.clientNonce(),
                stateInfo.dhParams(),
                stateInfo.knownServerCertHash(),
                netClient.getAuthClients().values().stream().map(GmaAuthClient::authType).toList(),
                new byte[0]
        );
        send(helloPacket);
        netClient.onConnectionReady();

    }

    @Override
    public void onConnectionClosed(String reason) {
        log.info("Connection closed with {}", uri);
        connectionStateCallback.onConnectionClosed(reason);
        isConnected = false;

    }

    @Override
    public void onConnectionError(Throwable e) {
        log.error("Error in the connection to {}", uri, e);
        isConnected = false;
        connectionStateCallback.onConnectionError(e);

    }

    @Override
    public void onConnectionReceive(byte[] data) {
        pipeline.decodeAndPassDown(data);

    }
}
