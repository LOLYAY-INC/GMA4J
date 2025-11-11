package io.lolyay.gma4j.net.crypto;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.util.Base64;

/**
 * Cryptographic utilities for secure communication.
 */
public final class CryptoUtils {
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private CryptoUtils() {}

    /**
     * Generate a Diffie-Hellman key pair for key exchange.
     */
    public static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    /**
     * Generate a random AES shared secret.
     */
    public static SecretKey generateSharedSecret() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGen.init(256);
        return keyGen.generateKey();
    }

    /**
     * Encrypt data using RSA public key.
     */
    public static String encryptWithPublicKey(byte[] data, PublicKey publicKey) 
            throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encrypted = cipher.doFinal(data);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * Decrypt data using RSA private key.
     */
    public static byte[] decryptWithPrivateKey(String encryptedData, PrivateKey privateKey) 
            throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWITHSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] encrypted = Base64.getDecoder().decode(encryptedData);
        return cipher.doFinal(encrypted);
    }

    /**
     * Encrypt data using AES-GCM with shared secret.
     */
    public static EncryptedData encrypt(String data, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        
        // Generate random IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(iv);
        
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        
        byte[] encrypted = cipher.doFinal(data.getBytes("UTF-8"));
        
        return new EncryptedData(
            Base64.getEncoder().encodeToString(encrypted),
            Base64.getEncoder().encodeToString(iv)
        );
    }

    /**
     * Decrypt data using AES-GCM with shared secret.
     */
    public static String decrypt(EncryptedData encryptedData, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        
        byte[] iv = Base64.getDecoder().decode(encryptedData.iv);
        byte[] encrypted = Base64.getDecoder().decode(encryptedData.data);
        
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);
        
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted, "UTF-8");
    }

    /**
     * Compute HMAC-SHA256 for challenge-response authentication.
     */
    public static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes("UTF-8"), HMAC_ALGORITHM);
        mac.init(secretKey);
        byte[] hmac = mac.doFinal(data.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(hmac);
    }

    /**
     * Generate a random challenge string.
     */
    public static String generateChallenge() {
        SecureRandom random = new SecureRandom();
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        return Base64.getEncoder().encodeToString(challenge);
    }

    /**
     * Encode public key to Base64 string.
     */
    public static String encodePublicKey(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * Decode public key from Base64 string.
     */
    public static PublicKey decodePublicKey(String encoded) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(encoded);
        return KeyFactory.getInstance("RSA").generatePublic(
            new java.security.spec.X509EncodedKeySpec(keyBytes)
        );
    }

    /**
     * Encode secret key to Base64 string.
     */
    public static String encodeSecretKey(SecretKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * Decode secret key from Base64 string.
     */
    public static SecretKey decodeSecretKey(String encoded) {
        byte[] keyBytes = Base64.getDecoder().decode(encoded);
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, KEY_ALGORITHM);
    }

    /**
     * Container for encrypted data with IV.
     */
    public static class EncryptedData {
        public final String data;
        public final String iv;

        public EncryptedData(String data, String iv) {
            this.data = data;
            this.iv = iv;
        }
    }
}
