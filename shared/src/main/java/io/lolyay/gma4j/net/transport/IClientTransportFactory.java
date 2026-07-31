package io.lolyay.gma4j.net.transport;

import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;

import java.util.Set;

public interface IClientTransportFactory {
    Set<String> schemes();

    IClientTransport create(ClientConnectionListener listener);
}
