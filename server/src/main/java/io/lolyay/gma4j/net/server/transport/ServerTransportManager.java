package io.lolyay.gma4j.net.server.transport;

import io.lolyay.gma4j.net.server.transport.netty.NettyServerTransportFactory;
import io.lolyay.gma4j.net.server.transport.ws.WsServerTransportFactory;
import io.lolyay.gma4j.net.transport.TransportManager;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ServerTransportManager {

    public void register() {
        TransportManager.registerServerFactory(new NettyServerTransportFactory());
        TransportManager.registerServerFactory(new WsServerTransportFactory());
    }
}
