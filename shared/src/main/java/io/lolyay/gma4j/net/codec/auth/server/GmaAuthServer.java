package io.lolyay.gma4j.net.codec.auth.server;

import io.lolyay.gma4j.net.codec.auth.IGmaAuth;

import java.util.UUID;

public interface GmaAuthServer extends IGmaAuth {
    byte[] createChallenge(UUID clientId, String claimedClientId, byte[] clientExtraData);
    boolean verifyClientResponse(byte[] serverChallenge, byte[] response, UUID clientId, String claimedClientId, byte[] stateHash);
}
