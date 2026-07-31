package io.lolyay.gma4j.net.server.systemcodec;

import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;
import io.lolyay.gma4j.net.codec.encryption.server.ServerEncryptionState;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthResponsePacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SHelloPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SKeepAlivePacket;
import io.lolyay.gma4j.net.codec.systemcodec.callbacks.SystemPacketCallback;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CAuthChallengePacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CAuthStatusPacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CHelloPacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CKeepAlivePacket;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ServerDefaultSystemPacketCallback implements SystemPacketCallback {
    private final ClientOnServer client;

    @Override
    public <T extends GMAPacket<T>> void onSystemPacket(T packet) {
        switch ((GMAPacket<?>) packet) {
            case C2SHelloPacket c2SHelloPacket -> onC2SHello(c2SHelloPacket);
            case C2SAuthPacket c2SAuthPacket -> onC2SAuth(c2SAuthPacket);
            case C2SAuthResponsePacket c2SAuthResponsePacket -> onC2SAuthResponse(c2SAuthResponsePacket);
            case C2SKeepAlivePacket c2SKeepAlivePacket -> client.send(new S2CKeepAlivePacket(c2SKeepAlivePacket.id()));

            default -> throw new IllegalStateException("Unexpected value: " + packet);
        }
    }

    private void onC2SHello(C2SHelloPacket packet) {
        String compatError = client.verifyCompatibility(packet);
        if(compatError != null) {
            log.error("Client {} is not compatible with server: {}", client.getRemoteId(), compatError);
            client.disconnect("Incompatible client, " + compatError);
            return;
        }

        S2CHelloPacket hello;
        try {
            ServerEncryptionState.ServerHelloResponse response = client.getEncryptionState().handleClientHello(
                    packet.connectUri(),
                    packet.supportedEncryptionModes(),
                    packet.clientNonce(),
                    packet.dhParams(),
                    packet.supportedAuthTypes()
            );
            GmaAuthServer authServer = client.getNetServer().getAuthServer(response.selectedAuthType());

            if(authServer == null) {
                log.error("No auth server registered for type {}", response.selectedAuthType());
                client.send(new S2CAuthStatusPacket(false));
                client.disconnect("Unsupported auth type");
                return;
            }
            client.setSelectedAuthServer(authServer);

            hello = new S2CHelloPacket(
                    response.selectedEncryptionMode(),
                    response.dhParams(),
                    response.cert(),
                    response.serverNonce(),
                    response.selectedAuthType(),
                    response.signature()
            );
        } catch (Exception e) {
            log.error("Failed to process client hello from {}", client.getRemoteId(), e);
            client.disconnect("Handshake failure");
            return;
        }

        client.send(hello);
        client.getPipeline().setCryptor(client.getEncryptionState().createPacketCryptor());
        log.info("Encryption established with {}", client.getRemoteId());
    }

    private void onC2SAuth(C2SAuthPacket packet) {
        if(client.getSelectedAuthServer() == null || client.getSelectedAuthServer().authType() != packet.authType()) {
            log.error("Client {} sent auth packet for unsupported auth type {}", client.getRemoteId(), packet.authType());
            client.disconnect("Unsupported auth type");
            return;
        }

        UUID assignedId = client.getNetServer().registerClient(client, packet.claimedClientId());
        if(assignedId == null) {
            log.warn("Rejecting {}: claimed id '{}' already in use", client.getRemoteId(), packet.claimedClientId());
            client.send(new S2CAuthStatusPacket(false));
            client.disconnect("Duplicate client id");
            return;
        }
        client.setAssignedId(assignedId);
        client.setClaimedClientId(packet.claimedClientId());

        GmaAuthServer authServer = client.getSelectedAuthServer();

        byte[] challenge = authServer.createChallenge(assignedId, packet.claimedClientId(), packet.extraAuthData());
        client.setPendingChallenge(challenge);
        client.send(new S2CAuthChallengePacket(challenge, assignedId));
    }

    private void onC2SAuthResponse(C2SAuthResponsePacket packet) {
        GmaAuthServer authServer = client.getSelectedAuthServer();

        if(authServer == null || client.getPendingChallenge() == null) {
            client.disconnect("Auth response before challenge");
            return;
        }

        boolean ok = authServer.verifyClientResponse(
                client.getPendingChallenge(),
                packet.response(),
                client.getAssignedId(),
                client.getClaimedClientId(),
                client.getEncryptionState().getStateHash()
        );

        if(!ok) {
            log.warn("Auth failed for {}", client.getClaimedClientId());
            client.send(new S2CAuthStatusPacket(false));
            client.disconnect("Auth failed");
            return;
        }

        client.setAuthenticated(true);
        client.send(new S2CAuthStatusPacket(true));
        log.info("Client authenticated: {} ({})", client.getClaimedClientId(), client.getAssignedId());
        client.getNetServer().getEventHandler().onClientAuthenticated(client);
    }
}
