package io.lolyay.gma4j.net.codec.connection;

public enum ConnectionState {
    HANDSHAKE,
    AUTH_CHALLENGE,
    AWAITING_AUTH_RESPONSE,
    CONNECTED

}
