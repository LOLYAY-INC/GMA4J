package io.lolyay.gma4j.net.server.transport.netty;

import io.lolyay.gma4j.net.codec.connection.server.ServerClientHandler;
import io.lolyay.gma4j.net.transport.IServerTransport;
import io.lolyay.gma4j.net.transport.IServerTransportFactory;
import io.lolyay.gma4j.net.transport.ServerTransportData;

import java.util.Set;

public class NettyServerTransportFactory implements IServerTransportFactory {

    @Override
    public Set<String> schemes() {
        return Set.of("gma4j", "gma");
    }

    @Override
    public IServerTransport create(ServerTransportData data, ServerClientHandler handler) {
        return new NettyServerTransport(data, handler);
    }
}
