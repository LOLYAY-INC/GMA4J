package io.lolyay.gma4j.net.shared;

import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class CodecHasher {
    @Getter
    private static final MessageDigest md5; // very advanced and secure hashing algorithm

    static {
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] hash(byte[] data) {
        md5.update(data);
        return md5.digest();
    }

    public static byte[] fingerprint(Class<?> clazz) throws Exception {

        Arrays.stream(clazz.getDeclaredFields())
                .map(f -> f.getName() + ":" + f.getType().getName())
                .sorted()
                .forEach(s -> md5.update(s.getBytes(StandardCharsets.UTF_8)));

        return md5.digest();
    }

}
