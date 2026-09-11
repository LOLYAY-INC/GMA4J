package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.client.cert.NoOpCertificateKeeper;
import io.lolyay.gma4j.net.client.net.GMA4JNetClient;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;
import io.lolyay.gma4j.net.codec.encryption.client.IClientKnownCertificateKeeper;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Arrays;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GMA4JClient {
    private final CodecRegistry codecRegistry = CodecRegistry.getInstance();
    @Getter
    private final ClientEventHandler clientEventHandler;
    @Setter
    @Getter
    private IClientKnownCertificateKeeper knownCertificateKeeper = new NoOpCertificateKeeper();

    /**
     * -- GETTER --
     * This method returns the raw netClient, it will **not** be documented, so use at your own risk.
     * @return The raw netClient if currently connected, null otherwise.
     */
    @Getter
    private GMA4JNetClient netClient;

    public void connect(ClientConnectionInfo clientConnectionInfo) {
        this.netClient = new GMA4JNetClient(clientEventHandler, clientConnectionInfo.clientId(), Arrays.stream(clientConnectionInfo.authClients())
                .collect(Collectors.toMap(GmaAuthClient::authType, Function.identity())), knownCertificateKeeper, codecRegistry);
        netClient.connect(clientConnectionInfo.uri().toString());
    }

    public <T extends GMAPacket<T>> void send(T packet) {
        netClient.send(packet);
    }

    /** Only skips coalescing when low latency mode is granted */
    public <T extends GMAPacket<T>> void sendUrgent(T packet) {
        netClient.sendUrgent(packet);
    }

    /**
     * Asks the server for connection modes, the grant arrives via onModesChanged
     */
    public void requestModes(boolean lowLatency, boolean bigSize, int requestedMaxPacketSize) {
        if(netClient == null) {
            throw new IllegalStateException("Not connected");
        }
        netClient.requestModes(lowLatency, bigSize, requestedMaxPacketSize);
    }

    public boolean isConnected() {
        return netClient != null && netClient.isConnected();
    }

    public void disconnect() {
        netClient.disconnect();
    }

    public void disconnectWithError(Exception e) {
        netClient.disconnectWithError(e);
    }


}