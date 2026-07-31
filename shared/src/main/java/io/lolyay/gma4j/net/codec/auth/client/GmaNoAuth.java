package io.lolyay.gma4j.net.codec.auth.client;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;

import java.util.UUID;

public class GmaNoAuth implements GmaAuthClient {
    @Override
    public GmaAuthType authType() {
        return GmaAuthType.NONE;
    }

    @Override
    public byte[] auth(byte[] serverChallenge, UUID internalClientId, String clientId, byte[] stateHash) {
        return new byte[0];
    }
}
