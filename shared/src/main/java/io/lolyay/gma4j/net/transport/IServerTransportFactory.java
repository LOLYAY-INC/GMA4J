package io.lolyay.gma4j.net.transport;

import io.lolyay.gma4j.net.codec.connection.server.ServerClientHandler;

import java.util.Set;

public interface IServerTransportFactory {
    Set<String> schemes();

    IServerTransport create(ServerTransportData data, ServerClientHandler handler);
}
