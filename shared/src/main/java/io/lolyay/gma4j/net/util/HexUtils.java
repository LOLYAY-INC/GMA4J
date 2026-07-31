package io.lolyay.gma4j.net.util;

public class HexUtils {
    /**
     * Converts a byte array to a hex string.
     *
     * @param b the byte array to convert
     * @return the hex string representation of the byte array
     */
    public static String convert(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte aB : b) {
            sb.append(String.format("%02x", aB));
        }
        return sb.toString();
    }

    /**
     * Converts a hex string to a byte array.
     *
     * @param s the hex string to convert
     * @return the byte array representation of the hex string
     */
    public static byte[] convert(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}
