package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;

public record ServerBindInfo(String host, int port, String scheme, GmaAuthServer... authServers) {

    public ServerBindInfo(String host, int port, GmaAuthServer... authServers) {
        this(host, port, "gma4j", authServers);
    }
}
