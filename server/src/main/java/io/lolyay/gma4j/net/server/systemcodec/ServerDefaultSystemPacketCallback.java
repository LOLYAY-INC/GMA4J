package io.lolyay.gma4j.net.server.systemcodec;

import io.lolyay.gma4j.net.codec.ClientType;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;
import io.lolyay.gma4j.net.codec.connection.ConnectionState;
import io.lolyay.gma4j.net.codec.encryption.server.ServerEncryptionState;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthResponsePacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SHelloPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SKeepAlivePacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SModeRequestPacket;
import io.lolyay.gma4j.net.codec.systemcodec.callbacks.SystemPacketCallback;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.*;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class ServerDefaultSystemPacketCallback implements SystemPacketCallback {
    private final ClientOnServer client;
    private ConnectionState connectionState = ConnectionState.HANDSHAKE;

    @Override
    public <T extends GMAPacket<T>> void onSystemPacket(T packet) {
        if(packet instanceof C2SKeepAlivePacket(long id)) {
            client.send(new S2CKeepAlivePacket(id));
            return;
        }

        if(packet instanceof C2SModeRequestPacket modeRequestPacket) {
            onC2SModeRequest(modeRequestPacket);
            return;
        }


        // Auth
        if(client.isAuthenticated()) {
            log.warn("Received packet {} from authenticated client {}", packet, client.getRemoteId());
            return;
        }

        switch ((GMAPacket<?>) packet) {
            case C2SHelloPacket c2SHelloPacket -> onC2SHello(c2SHelloPacket);
            case C2SAuthPacket c2SAuthPacket -> onC2SAuth(c2SAuthPacket);
            case C2SAuthResponsePacket c2SAuthResponsePacket -> onC2SAuthResponse(c2SAuthResponsePacket);
            default -> throw new IllegalStateException("Unexpected value: " + packet);
        }
    }

    private void onC2SHello(C2SHelloPacket packet) {
        if(connectionState != ConnectionState.HANDSHAKE) {
            log.error("Client {} sent hello packet while not in handshake state", client.getRemoteId());
            client.disconnect("Handshake failure");
            return;
        }

        String compatError = client.verifyCompatibility(packet);
        client.setClientType(packet.clientType());
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
        connectionState = ConnectionState.AUTH_CHALLENGE;

        client.send(hello);
        client.getPipeline().setCryptor(client.getEncryptionState().createPacketCryptor());
        log.info("Encryption established with {}", client.getRemoteId());
    }

    private void onC2SAuth(C2SAuthPacket packet) {
        if(connectionState != ConnectionState.AUTH_CHALLENGE) {
            log.error("Client {} sent auth packet while not in auth state", client.getRemoteId());
            client.disconnect("Handshake failure");
            return;
        }

        if(client.getSelectedAuthServer() == null || client.getSelectedAuthServer().authType() != packet.authType()) {
            log.error("Client {} sent auth packet for unsupported auth type {}", client.getRemoteId(), packet.authType());
            client.disconnect("Unsupported auth type");
            return;
        }

        UUID pendingAuthID = client.getNetServer().generateFreeUUID(packet.claimedClientId());

        client.setAssignedId(pendingAuthID);
        client.setClaimedClientId(packet.claimedClientId());

        GmaAuthServer authServer = client.getSelectedAuthServer();

        byte[] challenge = authServer.createChallenge(pendingAuthID, packet.claimedClientId(), packet.extraAuthData());
        client.setPendingChallenge(challenge);
        connectionState = ConnectionState.AWAITING_AUTH_RESPONSE;
        client.send(new S2CAuthChallengePacket(challenge, pendingAuthID));
    }

    private void onC2SAuthResponse(C2SAuthResponsePacket packet) {
        if(connectionState != ConnectionState.AWAITING_AUTH_RESPONSE) {
            log.error("Client {} sent auth response while not in auth state", client.getRemoteId());
            client.disconnect("Handshake failure");
            return;
        }

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

        UUID assignedId = client.getNetServer().registerClient(client);
        if(assignedId == null) {
            log.warn("Rejecting {}: claimed id '{}' already in use", client.getRemoteId(), client.getClaimedClientId());
            client.send(new S2CAuthStatusPacket(false));
            client.disconnect("Duplicate client id");
            return;
        }


        if(client.getClientType() != ClientType.GMA4J_JAVA) {
            // Send Compat packet
            client.send(S2CCodecStateUpdatePacket.of(CodecRegistry.getInstance().getConfig()));
        }

        client.setAuthenticated(true);
        connectionState = ConnectionState.CONNECTED;
        client.send(new S2CAuthStatusPacket(true));
        log.info("Client authenticated: {} ({})", client.getClaimedClientId(), client.getAssignedId());
        client.getNetServer().getEventHandler().onClientAuthenticated(client);
    }

    private void onC2SModeRequest(C2SModeRequestPacket packet) {
        if(!client.isAuthenticated()) {
            client.disconnect("Mode request before auth");
            return;
        }
        if(packet.requestedMaxPacketSize() < 0) {
            client.disconnect("Negative mode packet size");
            return;
        }
        if(!client.modeChangeAllowed()) {
            log.warn("Rate limited mode request from {}", client.getClaimedClientId());
            return;
        }
        client.setModes(packet.lowLatency(), packet.bigSize(), packet.requestedMaxPacketSize());
    }
}
