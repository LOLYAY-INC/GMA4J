package io.lolyay.gma4j.net.codec.auth.server;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.util.LongUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

import static io.lolyay.gma4j.net.shared.SharedConfig.ALLOWED_CLOCK_SKEW_MS;

@Slf4j
public class GmaApiECCAuthServer implements GmaAuthServer {
    private final ECPublicKey ecpublicKey;
    private final static SecureRandom random = new SecureRandom();

    public GmaApiECCAuthServer(ECPublicKey ecpublicKey) {
        this.ecpublicKey = ecpublicKey;
    }

    public GmaApiECCAuthServer(String publicKey) {
        this(importPublicKey(publicKey));
    }


    private static ECPublicKey importPublicKey(String publicKey) {
        try {
            String cleaned = publicKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", ""); // strips newlines/spaces

            byte[] decoded = Base64.getDecoder().decode(cleaned);

            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return (ECPublicKey) keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Error importing EC Public Key", e);
        }
    }


    @Override
    public GmaAuthType authType() {
        return GmaAuthType.ECC_ECDSA;
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

        byte[] signature = new byte[response.length - 8];
        System.arraycopy(response, 8, signature, 0, signature.length);

        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > ALLOWED_CLOCK_SKEW_MS) {
            return false;
        }

        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(ecpublicKey);
            verifier.update(GMA_AUTH_CONTEXT);
            verifier.update(stateHash);
            verifier.update(claimedClientId.getBytes(StandardCharsets.UTF_8));
            verifier.update(uuidToBytes(clientId));
            verifier.update(tsBytes);
            verifier.update(serverChallenge);
            return verifier.verify(signature);
        } catch (Exception e) {
            log.debug("Error verifying client response", e);
            return false;
        }
    }
}
