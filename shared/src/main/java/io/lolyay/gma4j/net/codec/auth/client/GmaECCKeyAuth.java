package io.lolyay.gma4j.net.codec.auth.client;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.util.LongUtil;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class GmaECCKeyAuth implements GmaAuthClient {
    private final Signature sign;

    public static GmaECCKeyAuth of(String privateKey) {
        String sanitized = privateKey
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN EC PRIVATE KEY-----", "")
                .replace("-----END EC PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        byte[] encoded = Base64.getDecoder().decode(sanitized);

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            ECPrivateKey parsed = (ECPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
            return GmaECCKeyAuth.of(parsed);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid EC private key", e);
        }
    }

    @SneakyThrows
    public static GmaECCKeyAuth of(ECPrivateKey privateKey) {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        return new GmaECCKeyAuth(signature);
    }


    @Override
    public GmaAuthType authType() {
        return GmaAuthType.ECC_ECDSA;
    }

    @Override
    public synchronized byte[] auth(byte[] serverChallenge, UUID internalClientId, String clientId, byte[] stateHash) {
        try {
            long ts = System.currentTimeMillis();
            sign.update(GMA_AUTH_CONTEXT);
            sign.update(stateHash);
            sign.update(clientId.getBytes(StandardCharsets.UTF_8));
            sign.update(uuidToBytes(internalClientId));
            sign.update(LongUtil.longToBytes(ts));
            sign.update(serverChallenge);
            byte[] signature = sign.sign();
            byte[] response = new byte[signature.length + 8];
            System.arraycopy(LongUtil.longToBytes(ts), 0, response, 0, 8);
            System.arraycopy(signature, 0, response, 8, signature.length);
            return response;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign server challenge", e);
        }
    }



}
