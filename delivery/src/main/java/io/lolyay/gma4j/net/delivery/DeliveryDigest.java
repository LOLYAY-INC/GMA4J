package io.lolyay.gma4j.net.delivery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class DeliveryDigest {
    public static final int LENGTH = 32;

    private DeliveryDigest() {
    }

    public static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
