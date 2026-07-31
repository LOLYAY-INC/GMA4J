package io.lolyay.gma4j.net.codec.connection;

public interface MessageSender {
    boolean send(byte[] data);

    void close();
}
