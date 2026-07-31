package io.lolyay.gma4j.net.transport.ws;

import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;
import io.lolyay.gma4j.net.transport.IClientTransport;
import io.lolyay.gma4j.net.transport.IClientTransportFactory;

import java.util.Set;

public class WsClientTransportFactory implements IClientTransportFactory {

    @Override
    public Set<String> schemes() {
        return Set.of("ws", "wss");
    }

    @Override
    public IClientTransport create(ClientConnectionListener listener) {
        return new WsClientTransport(listener);
    }
}
