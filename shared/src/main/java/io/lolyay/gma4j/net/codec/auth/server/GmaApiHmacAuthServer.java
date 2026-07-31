package io.lolyay.gma4j.net.codec.auth.server;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.util.LongUtil;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

import static io.lolyay.gma4j.net.shared.SharedConfig.ALLOWED_CLOCK_SKEW_MS;

@Slf4j
public class GmaApiHmacAuthServer implements GmaAuthServer {
    private final static SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public GmaApiHmacAuthServer(String secret) {
        this(secret.getBytes(StandardCharsets.UTF_8));
    }

    public GmaApiHmacAuthServer(byte[] secret) {
        this.secret = secret;
    }

    @Override
    public GmaAuthType authType() {
        return GmaAuthType.HMAC_SHA256;
    }

    @Override
    public byte[] createChallenge(UUID clientId, String claimedClientId, byte[] clientExtraData) {
        byte[] challenge = new byte[256];
        random.nextBytes(challenge); // nonce fills everything
        return challenge;
    }

    @Override
    public boolean verifyClientResponse(byte[] serverChallenge, byte[] response, UUID clientId, String claimedClientId, byte[] stateHash) {
        if (response.length <= 8) {
            return false;
        }

        byte[] tsBytes = new byte[8];
        System.arraycopy(response, 0, tsBytes, 0, 8);
        long ts = LongUtil.bytesToLong(tsBytes);

        byte[] tag = new byte[response.length - 8];
        System.arraycopy(response, 8, tag, 0, tag.length);

        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > ALLOWED_CLOCK_SKEW_MS) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update(GMA_AUTH_CONTEXT);
            mac.update(stateHash);
            mac.update(claimedClientId.getBytes(StandardCharsets.UTF_8));
            mac.update(uuidToBytes(clientId));
            mac.update(tsBytes);
            mac.update(serverChallenge);
            byte[] expected = mac.doFinal();

            return MessageDigest.isEqual(expected, tag);
        } catch (Exception e) {
            log.debug("Error verifying HMAC client response", e);
            return false;
        }
    }
}
