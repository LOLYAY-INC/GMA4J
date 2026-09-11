package io.lolyay.gma4j.net.codec.connection;

public interface IConnectionStateCallback {
    void onConnectionEstablished();
    void onConnectionClosed(String reason);
    void onConnectionError(Throwable e);
    void onAuthSuccess();

    default void onModesChanged(boolean lowLatency, boolean bigSize, int maxPacketSize) {
    }
}
