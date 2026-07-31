package io.lolyay.gma4j.net.codec.auth.client;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.util.LongUtil;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class GmaHMACKeyAuth implements GmaAuthClient {
    private final byte[] secret;

    public static GmaHMACKeyAuth of(String secret) {
        return new GmaHMACKeyAuth(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static GmaHMACKeyAuth of(byte[] secret) {
        return new GmaHMACKeyAuth(secret.clone());
    }

    @Override
    public GmaAuthType authType() {
        return GmaAuthType.HMAC_SHA256;
    }

    @Override
    public byte[] auth(byte[] serverChallenge, UUID internalClientId, String clientId, byte[] stateHash) {
        try {
            long ts = System.currentTimeMillis();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(GMA_AUTH_CONTEXT);
            mac.update(stateHash);
            mac.update(clientId.getBytes(StandardCharsets.UTF_8));
            mac.update(uuidToBytes(internalClientId));
            mac.update(LongUtil.longToBytes(ts));
            mac.update(serverChallenge);
            byte[] signature = mac.doFinal();
            byte[] response = new byte[signature.length + 8];
            System.arraycopy(LongUtil.longToBytes(ts), 0, response, 0, 8);
            System.arraycopy(signature, 0, response, 8, signature.length);
            return response;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to HMAC server challenge", e);
        }
    }



}
