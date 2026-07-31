package io.lolyay.gma4j.net.codec;

public class PacketCodingException extends RuntimeException {
    public PacketCodingException(String message) {
        super(message);
    }

    public PacketCodingException(String message, Exception e) {
        super(message, e);
    }
}
