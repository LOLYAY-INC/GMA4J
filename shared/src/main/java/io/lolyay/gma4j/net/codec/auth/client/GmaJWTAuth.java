package io.lolyay.gma4j.net.codec.auth.client;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class GmaJWTAuth implements GmaAuthClient {
    private final String jwt;

    public static GmaJWTAuth of(String jwt) {
        return new GmaJWTAuth(jwt);
    }

    @Override
    public GmaAuthType authType() {
        return GmaAuthType.JWT;
    }

    @Override
    public byte[] auth(byte[] serverChallenge, UUID internalClientId, String clientId, byte[] stateHash) {
        return jwt.getBytes(StandardCharsets.UTF_8);
    }

}
