package io.lolyay.gma4j.net.codec.encryption;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public class PacketCryptor {

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private static final byte[] LABEL_C2S = "gma4j c2s packet key".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_S2C = "gma4j s2c packet key".getBytes(StandardCharsets.US_ASCII);

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec sendKey;
    private final SecretKeySpec receiveKey;

    public PacketCryptor(byte[] masterSecret, boolean serverSide) {
        byte[] c2s = deriveKey(masterSecret, LABEL_C2S);
        byte[] s2c = deriveKey(masterSecret, LABEL_S2C);
        this.sendKey = new SecretKeySpec(serverSide ? s2c : c2s, "AES");
        this.receiveKey = new SecretKeySpec(serverSide ? c2s : s2c, "AES");
    }

    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, sendKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] out = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, out, IV_LENGTH, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Packet encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] packet) {
        if (packet.length < IV_LENGTH + TAG_LENGTH_BITS / 8) {
            throw new IllegalArgumentException("Ciphertext too short: " + packet.length + " bytes");
        }
        try {
            byte[] iv = Arrays.copyOfRange(packet, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, receiveKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(packet, IV_LENGTH, packet.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Packet decryption failed", e);
        }
    }

    private byte[] deriveKey(byte[] masterSecret, byte[] label) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(masterSecret, HMAC_ALGO));
            mac.update(label);
            mac.update((byte) 0x01);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Packet key derivation failed", e);
        }
    }
}
