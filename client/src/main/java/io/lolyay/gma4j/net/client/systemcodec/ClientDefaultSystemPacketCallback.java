package io.lolyay.gma4j.net.client.systemcodec;

import io.lolyay.gma4j.net.client.net.GMA4JNetClient;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;
import io.lolyay.gma4j.net.codec.connection.ConnectionState;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthResponsePacket;
import io.lolyay.gma4j.net.codec.systemcodec.callbacks.SystemPacketCallback;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ClientDefaultSystemPacketCallback implements SystemPacketCallback {
    private final GMA4JNetClient netClient;
    private GmaAuthClient selectedAuthClient;
    private ConnectionState connectionState = ConnectionState.HANDSHAKE;

    @Override
    public <T extends GMAPacket<T>> void onSystemPacket(T packet) {

        if(packet instanceof S2CKeepAlivePacket(long id)) {
            netClient.onKeepAlive();
            return;
        }

        if(packet instanceof S2CCodecStateUpdatePacket) {
            log.warn("Received codec state update packet, even tho we are Java?");
            return;
        }

        if(netClient.isAuthenticated()) {
            if(netClient.getParent().allowReAuth)
                log.info("Reauthenticating");
            else {
                log.warn("Server sent auth packet while authenticated, but reauth is disabled");
                return;
            }
        }

        // Auth
        switch ((GMAPacket<?>) packet) {
            case S2CHelloPacket s2cHelloPacket -> onS2CHello(s2cHelloPacket);
            case S2CAuthChallengePacket s2CAuthChallengePacket -> onS2CAuthChallenge(s2CAuthChallengePacket);
            case S2CAuthStatusPacket s2CAuthStatusPacket -> onAuthStatus(s2CAuthStatusPacket);
            default -> throw new IllegalStateException("Unexpected value: " + packet);
        }
    }

    protected void onAuthStatus(S2CAuthStatusPacket authStatusPacket) {
        if(connectionState != ConnectionState.AWAITING_AUTH_RESPONSE) {
            log.error("Server sent auth status while not in auth state");
            netClient.disconnectWithError(new Exception("Server sent auth status while not in auth state"));
            return;
        }

        if(!authStatusPacket.success()) {
            log.error("Auth failed!");
            netClient.disconnectWithError(new Exception("Auth failed"));
            return;
        }

        log.info("Auth successful!");
        netClient.onAuthenticated();
        netClient.getPacketHandler().onAuthSuccess();
        connectionState = ConnectionState.CONNECTED;
    }

    private void onS2CAuthChallenge(S2CAuthChallengePacket s2CAuthChallengePacket) {
        if(connectionState != ConnectionState.AUTH_CHALLENGE) {
            log.error("Server sent auth challenge while not in auth state");
            netClient.disconnectWithError(new Exception("Server sent auth challenge while not in auth state"));
            return;
        }

        if(selectedAuthClient == null) {
            log.error("Server sent auth challenge, but no auth client selected");
            netClient.disconnectWithError(new Exception("No auth client selected"));
            netClient.disconnect();
            return;
        }
        netClient.setClientId(s2CAuthChallengePacket.clientId());
        log.debug("Internal client id: {}", s2CAuthChallengePacket.clientId());

        byte[] response = selectedAuthClient.auth(s2CAuthChallengePacket.challenge(), s2CAuthChallengePacket.clientId(), netClient.getClaimedClientId(), netClient.getClientEncryptionState().getStateHash());

        C2SAuthResponsePacket authResponsePacket = new C2SAuthResponsePacket(response);
        log.info("Authenticating with {}", selectedAuthClient.authType());
        connectionState = ConnectionState.AWAITING_AUTH_RESPONSE;
        netClient.send(authResponsePacket);
    }

    private void onS2CHello(S2CHelloPacket s2CHelloPacket) {
        boolean shouldAllowReAuth =
                netClient.isAuthenticated() && connectionState == ConnectionState.CONNECTED && netClient.getParent().allowReAuth;
        if(connectionState != ConnectionState.HANDSHAKE && !shouldAllowReAuth) {
            log.error("Server sent hello packet while not in handshake state");
            netClient.disconnectWithError(new Exception("Server sent hello packet while not in handshake state"));
            return;
        }

        try {
            netClient.getClientEncryptionState().parseServerResponse(
                    s2CHelloPacket.selectedEncryptionMode(),
                    s2CHelloPacket.dhParams(),
                    s2CHelloPacket.cert(),
                    s2CHelloPacket.serverNonce(),
                    s2CHelloPacket.selectedAuthType(),
                    s2CHelloPacket.signature(),
                    () -> netClient.getAuthClients().values().stream().map(GmaAuthClient::authType).toList()
            );
        } catch (Exception e) {
            log.error("Error establishing encryption", e);
            netClient.disconnectWithError(new RuntimeException("Error establishing encryption",e));
            return;
        }

        netClient.getPipeline().setCryptor(netClient.getClientEncryptionState().createPacketCryptor());
        log.info("Encryption established");

        selectedAuthClient = netClient.getAuthClients().get(s2CHelloPacket.selectedAuthType());
        if (selectedAuthClient == null) {
            log.error("No auth client registered for auth type {} that server Selected", s2CHelloPacket.selectedAuthType());
            netClient.disconnectWithError(new RuntimeException("No auth client registered for auth type " + s2CHelloPacket.selectedAuthType()));
            return;
        }

        C2SAuthPacket authPacket = new C2SAuthPacket(selectedAuthClient.authType(), selectedAuthClient.extraAuthData(), netClient.getClaimedClientId());
        connectionState = ConnectionState.AUTH_CHALLENGE;
        netClient.send(authPacket);
    }
}
