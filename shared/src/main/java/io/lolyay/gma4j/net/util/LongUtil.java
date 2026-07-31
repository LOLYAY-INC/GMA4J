package io.lolyay.gma4j.net.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class LongUtil {
    public void longToBytes(long l, byte[] bytes) {
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (l >>> (56 - i * 8));
        }
    }

    public byte[] longToBytes(long l) {
        byte[] bytes = new byte[8];
        longToBytes(l, bytes);
        return bytes;
    }

    public long bytesToLong(byte[] bytes) {
        long l = 0;
        for (int i = 0; i < 8; i++) {
            l |= (bytes[i] & 0xFFL) << (56 - i * 8);
        }
        return l;
    }
}
