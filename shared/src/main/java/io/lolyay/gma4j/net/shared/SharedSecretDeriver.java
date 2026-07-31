package io.lolyay.gma4j.net.shared;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.encryption.EncryptionMode;
import lombok.experimental.UtilityClass;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;

@UtilityClass
public class SharedSecretDeriver {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int HASH_LEN = 32;

    public void derive(byte[] dhSharedSecret, byte[] packetHash, byte[] serverNonce, byte[] clientNonce,
                       GmaAuthType authType, List<EncryptionMode> clientSideEncryptionModesProposed,
                       String connectingUri, byte[] dest) {
        Objects.requireNonNull(dhSharedSecret, "dhSharedSecret");
        Objects.requireNonNull(packetHash, "packetHash");
        Objects.requireNonNull(serverNonce, "serverNonce");
        Objects.requireNonNull(clientNonce, "clientNonce");
        Objects.requireNonNull(authType, "authType");
        Objects.requireNonNull(clientSideEncryptionModesProposed, "clientSideEncryptionModesProposed");
        Objects.requireNonNull(dest, "dest");
        if (dest.length < HASH_LEN) {
            throw new IllegalArgumentException("dest must be at least " + HASH_LEN + " bytes");
        }

        try {
            byte[] salt = concat(serverNonce, clientNonce);
            byte[] prk = hmac(salt, dhSharedSecret);

            byte[] info = buildInfo(packetHash, authType, clientSideEncryptionModesProposed, connectingUri);

            byte[] t1 = hmacExpandBlock(prk, info);

            System.arraycopy(t1, 0, dest, 0, HASH_LEN);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HKDF derivation failed", e);
        }
    }

    private byte[] buildInfo(byte[] packetHash, GmaAuthType authType,
                             List<EncryptionMode> modes, String connectingUri) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLengthPrefixed(out, packetHash);
        writeLengthPrefixed(out, authType.name().getBytes(StandardCharsets.UTF_8));

        writeInt(out, modes.size());
        for (EncryptionMode mode : modes) {
            writeLengthPrefixed(out, mode.name().getBytes(StandardCharsets.UTF_8));
        }

        if (connectingUri != null) {
            writeInt(out, 1);
            writeLengthPrefixed(out, connectingUri.getBytes(StandardCharsets.UTF_8));
        } else {
            writeInt(out, 0);
        }

        return out.toByteArray();
    }

    private void writeLengthPrefixed(ByteArrayOutputStream out, byte[] data) {
        writeInt(out, data.length);
        out.write(data, 0, data.length);
    }

    private void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(key, HMAC_ALGO));
        return mac.doFinal(data);
    }

    private byte[] hmacExpandBlock(byte[] prk, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(prk, HMAC_ALGO));
        mac.update(info);
        mac.update((byte) 0x01);
        return mac.doFinal();
    }
}