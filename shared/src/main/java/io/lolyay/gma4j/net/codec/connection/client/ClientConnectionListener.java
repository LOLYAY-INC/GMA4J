package io.lolyay.gma4j.net.codec.connection.client;

import io.lolyay.gma4j.net.codec.connection.ConnectionListener;

import javax.net.ssl.SSLContext;

public interface ClientConnectionListener extends ConnectionListener {

    /** Custom TLS trust for wss, null uses the transport default (system CAs) */
    default SSLContext sslContext() {
        return null;
    }
}
