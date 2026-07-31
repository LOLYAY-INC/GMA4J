package io.lolyay.gma4j.net.transport.netty;

import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;
import io.lolyay.gma4j.net.transport.IClientTransport;
import io.lolyay.gma4j.net.transport.IClientTransportFactory;

import java.util.Set;

public class NettyClientTransportFactory implements IClientTransportFactory {

    @Override
    public Set<String> schemes() {
        return Set.of("gma4j", "gma");
    }

    @Override
    public IClientTransport create(ClientConnectionListener listener) {
        return new NettyClientTransport(listener);
    }
}
