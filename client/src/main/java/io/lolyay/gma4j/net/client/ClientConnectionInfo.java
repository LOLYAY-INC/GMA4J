package io.lolyay.gma4j.net.client;

import io.lolyay.gma4j.net.codec.auth.client.ClientAuth;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.UUID;

@Slf4j
public record ClientConnectionInfo(String clientId, URI uri, GmaAuthClient... authClients) {

    public ClientConnectionInfo(URI uri) {
        this(UUID.randomUUID().toString(), uri, ClientAuth.none());
        log.warn("No auth client provided, using no auth.");
    }

    public ClientConnectionInfo(URI uri, String clientId) {
        this(clientId, uri, ClientAuth.none());
        log.warn("No auth client provided, using no auth.");
    }

}
