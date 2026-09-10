package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.net.codec.auth.IGmaAuth;
import io.lolyay.gma4j.net.codec.auth.client.GmaECCKeyAuth;
import io.lolyay.gma4j.net.codec.auth.client.GmaHMACKeyAuth;
import io.lolyay.gma4j.net.codec.auth.server.GmaApiECCAuthServer;
import io.lolyay.gma4j.net.codec.auth.server.GmaApiHmacAuthServer;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.util.LongUtil;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRoundTripTest {

    @Test
    void hmacBindsEveryAuthenticationField() {
        byte[] secret = "shared-secret".getBytes(StandardCharsets.UTF_8);
        byte[] challenge = bytes(256, 1);
        byte[] stateHash = bytes(32, 2);
        UUID clientId = UUID.randomUUID();
        String claimedId = "client-1";
        byte[] response = GmaHMACKeyAuth.of(secret).auth(challenge, clientId, claimedId, stateHash);
        GmaApiHmacAuthServer server = new GmaApiHmacAuthServer(secret);

        assertTrue(server.verifyClientResponse(challenge, response, clientId, claimedId, stateHash));
        assertFalse(new GmaApiHmacAuthServer("wrong").verifyClientResponse(
                challenge, response, clientId, claimedId, stateHash));
        assertFalse(server.verifyClientResponse(changed(challenge), response, clientId, claimedId, stateHash));
        assertFalse(server.verifyClientResponse(challenge, response, UUID.randomUUID(), claimedId, stateHash));
        assertFalse(server.verifyClientResponse(challenge, response, clientId, "client-2", stateHash));
        assertFalse(server.verifyClientResponse(challenge, response, clientId, claimedId, changed(stateHash)));
        assertFalse(server.verifyClientResponse(challenge, changed(response), clientId, claimedId, stateHash));
        assertFalse(server.verifyClientResponse(challenge, new byte[8], clientId, claimedId, stateHash));
    }

    @Test
    void eccBindsEveryAuthenticationField() throws Exception {
        KeyPair pair = keyPair();
        byte[] challenge = bytes(256, 3);
        byte[] stateHash = bytes(32, 4);
        UUID clientId = UUID.randomUUID();
        String claimedId = "client-1";
        byte[] response = GmaECCKeyAuth.of((ECPrivateKey) pair.getPrivate())
                .auth(challenge, clientId, claimedId, stateHash);
        GmaApiECCAuthServer server = new GmaApiECCAuthServer((ECPublicKey) pair.getPublic());

        assertTrue(server.verifyClientResponse(challenge, response, clientId, claimedId, stateHash));
        assertFalse(new GmaApiECCAuthServer((ECPublicKey) keyPair().getPublic())
                .verifyClientResponse(challenge, response, clientId, claimedId, stateHash));
        assertFalse(server.verifyClientResponse(changed(challenge), response, clientId, claimedId, stateHash));
        assertFalse(server.verifyClientResponse(challenge, response, UUID.randomUUID(), claimedId, stateHash));
        assertFalse(server.verifyClientResponse(challenge, response, clientId, "client-2", stateHash));
        assertFalse(server.verifyClientResponse(challenge, response, clientId, claimedId, changed(stateHash)));
        assertFalse(server.verifyClientResponse(challenge, changed(response), clientId, claimedId, stateHash));
        assertFalse(server.verifyClientResponse(challenge, new byte[8], clientId, claimedId, stateHash));
    }

    @Test
    void rejectsValidResponsesWithExtremeTimestamps() throws Exception {
        byte[] secret = "shared-secret".getBytes(StandardCharsets.UTF_8);
        byte[] challenge = bytes(256, 5);
        byte[] stateHash = bytes(32, 6);
        UUID clientId = UUID.randomUUID();
        String claimedId = "client-1";

        byte[] hmac = hmacResponse(secret, Long.MIN_VALUE, challenge, clientId, claimedId, stateHash);
        assertFalse(new GmaApiHmacAuthServer(secret)
                .verifyClientResponse(challenge, hmac, clientId, claimedId, stateHash));

        KeyPair pair = keyPair();
        byte[] ecc = eccResponse((ECPrivateKey) pair.getPrivate(), Long.MIN_VALUE,
                challenge, clientId, claimedId, stateHash);
        assertFalse(new GmaApiECCAuthServer((ECPublicKey) pair.getPublic())
                .verifyClientResponse(challenge, ecc, clientId, claimedId, stateHash));
    }

    @Test
    void acceptsResponsesWithinConfiguredSkew() throws Exception {
        byte[] secret = "shared-secret".getBytes(StandardCharsets.UTF_8);
        byte[] challenge = bytes(256, 7);
        byte[] stateHash = bytes(32, 8);
        UUID clientId = UUID.randomUUID();
        String claimedId = "client-1";
        long timestamp = System.currentTimeMillis() - Math.max(1, SharedConfig.ALLOWED_CLOCK_SKEW_MS / 2);
        byte[] response = hmacResponse(secret, timestamp, challenge, clientId, claimedId, stateHash);
        assertTrue(new GmaApiHmacAuthServer(secret)
                .verifyClientResponse(challenge, response, clientId, claimedId, stateHash));
    }

    private static byte[] hmacResponse(byte[] secret, long timestamp, byte[] challenge,
                                       UUID clientId, String claimedId, byte[] stateHash) throws Exception {
        byte[] timestampBytes = LongUtil.longToBytes(timestamp);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        update(mac, timestampBytes, challenge, clientId, claimedId, stateHash);
        return response(timestampBytes, mac.doFinal());
    }

    private static byte[] eccResponse(ECPrivateKey key, long timestamp, byte[] challenge,
                                      UUID clientId, String claimedId, byte[] stateHash) throws Exception {
        byte[] timestampBytes = LongUtil.longToBytes(timestamp);
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(IGmaAuth.GMA_AUTH_CONTEXT);
        signature.update(stateHash);
        signature.update(claimedId.getBytes(StandardCharsets.UTF_8));
        signature.update(uuidBytes(clientId));
        signature.update(timestampBytes);
        signature.update(challenge);
        return response(timestampBytes, signature.sign());
    }

    private static void update(Mac mac, byte[] timestampBytes, byte[] challenge,
                               UUID clientId, String claimedId, byte[] stateHash) {
        mac.update(IGmaAuth.GMA_AUTH_CONTEXT);
        mac.update(stateHash);
        mac.update(claimedId.getBytes(StandardCharsets.UTF_8));
        mac.update(uuidBytes(clientId));
        mac.update(timestampBytes);
        mac.update(challenge);
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static byte[] response(byte[] timestamp, byte[] proof) {
        byte[] response = Arrays.copyOf(timestamp, timestamp.length + proof.length);
        System.arraycopy(proof, 0, response, timestamp.length, proof.length);
        return response;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] bytes(int size, int seed) {
        byte[] value = new byte[size];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) (seed + i * 31);
        }
        return value;
    }

    private static byte[] changed(byte[] value) {
        byte[] copy = value.clone();
        copy[copy.length - 1] ^= 1;
        return copy;
    }
}
