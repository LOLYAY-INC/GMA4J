package io.lolyay.gma4j.net.codec.auth.client;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class GmaApiKeyAuth implements GmaAuthClient {
    private final String key;

    public static GmaApiKeyAuth of(String key) {
        return new GmaApiKeyAuth(key);
    }


    @Override
    public GmaAuthType authType() {
        return GmaAuthType.API_KEY;
    }

    @Override
    public byte[] auth(byte[] serverChallenge, UUID internalClientId, String clientId, byte[] stateHash) {
        return Base64.getEncoder().encode((internalClientId.toString() + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

}
