package io.lolyay.gma4j.net.codec.connection;

import io.lolyay.gma4j.net.shared.SharedConfig;

public interface ConnectionListener {


    /**
     * @param sender A way to send bytes upstream
     */
    void onConnectionEstablished(MessageSender sender);
    void onConnectionClosed(String reason);
    void onConnectionError(Throwable e);
    void onConnectionReceive(byte[] data);

    /**
     * Current inbound frame cap, transports re-read this per frame
     */
    default int maxIncomingFrameSize() {
        return SharedConfig.MAX_PACKET_SIZE;
    }
}
