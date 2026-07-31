package io.lolyay.gma4j.net.codec.connection;

public interface ConnectionListener {


    /**
     * @param sender A way to send bytes upstream
     */
    void onConnectionEstablished(MessageSender sender);
    void onConnectionClosed(String reason);
    void onConnectionError(Throwable e);
    void onConnectionReceive(byte[] data);
}
