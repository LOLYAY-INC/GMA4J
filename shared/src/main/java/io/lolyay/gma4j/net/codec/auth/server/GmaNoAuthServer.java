package io.lolyay.gma4j.net.codec.auth.server;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class GmaNoAuthServer implements GmaAuthServer {
    public GmaNoAuthServer() {
        log.warn("WARNING! NONE Auth is enabled, this allows any client to connect without any authentication.");
    }

    @Override
    public GmaAuthType authType() {
        return GmaAuthType.NONE;
    }

    @Override
    public byte[] createChallenge(UUID clientId, String claimedClientId, byte[] clientExtraData) {
        return new byte[0];
    }

    @Override
    public boolean verifyClientResponse(byte[] serverChallenge, byte[] response, UUID clientId, String claimedClientId, byte[] stateHash) {
        return true;
    }

}