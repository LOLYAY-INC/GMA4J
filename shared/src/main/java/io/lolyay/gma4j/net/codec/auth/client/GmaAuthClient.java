package io.lolyay.gma4j.net.codec.auth.client;

import io.lolyay.gma4j.net.codec.auth.IGmaAuth;

import java.util.UUID;

public interface GmaAuthClient extends IGmaAuth {
    byte[] auth(byte[] serverChallenge, UUID internalClientId, String clientId, byte[] stateHash);
    default byte[] extraAuthData(){return new byte[0];}
}
