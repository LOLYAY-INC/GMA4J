package io.lolyay.gma4j.net.client.systemcodec;

import io.lolyay.gma4j.net.client.net.GMA4JNetClient;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthResponsePacket;
import io.lolyay.gma4j.net.codec.systemcodec.callbacks.SystemPacketCallback;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CAuthChallengePacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CAuthStatusPacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CHelloPacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CKeepAlivePacket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ClientDefaultSystemPacketCallback implements SystemPacketCallback {
    private final GMA4JNetClient netClient;
    private GmaAuthClient selectedAuthClient;

    @Override
    public <T extends GMAPacket<T>> void onSystemPacket(T packet) {
        switch ((GMAPacket<?>) packet) {
            case S2CHelloPacket s2cHelloPacket -> onS2CHello(s2cHelloPacket);
            case S2CAuthChallengePacket s2CAuthChallengePacket -> onS2CAuthChallenge(s2CAuthChallengePacket);
            case S2CAuthStatusPacket s2CAuthStatusPacket -> onAuthStatus(s2CAuthStatusPacket);
            case S2CKeepAlivePacket s2CKeepAlivePacket -> netClient.onKeepAlive();

            default -> throw new IllegalStateException("Unexpected value: " + packet);
        }
    }

    protected void onAuthStatus(S2CAuthStatusPacket authStatusPacket) {
        if(!authStatusPacket.success()) {
            log.error("Auth failed!");
            netClient.disconnectWithError(new Exception("Auth failed"));
            return;
        }

        log.info("Auth successful!");
        netClient.onAuthenticated();
        netClient.getPacketHandler().onAuthSuccess();
    }

    private void onS2CAuthChallenge(S2CAuthChallengePacket s2CAuthChallengePacket) {
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
        netClient.send(authResponsePacket);
    }

    private void onS2CHello(S2CHelloPacket s2CHelloPacket) {
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
        netClient.send(authPacket);
    }
}
