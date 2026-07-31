package io.lolyay.gma4j.net.server;

import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.server.cert.FileServerCertificateProvider;
import io.lolyay.gma4j.net.server.net.GMA4JNetServer;
import io.lolyay.gma4j.net.transport.ServerTransportData;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class GMA4JServer {
    private final CodecRegistry codecRegistry = CodecRegistry.getInstance();
    @Getter
    private final ServerEventHandler eventHandler;
    @Getter
    private final IServerCertificateProvider certificateProvider;

    @Getter
    private GMA4JNetServer netServer;

    public GMA4JServer(ServerEventHandler eventHandler, Path basePath) {
        this.eventHandler = eventHandler;
        this.certificateProvider = new FileServerCertificateProvider(basePath);
    }

    public void start(ServerBindInfo bindInfo) {
        Map<GmaAuthType, GmaAuthServer> authServers = Arrays.stream(bindInfo.authServers())
                .collect(Collectors.toMap(GmaAuthServer::authType, Function.identity()));
        this.netServer = new GMA4JNetServer(eventHandler, certificateProvider, authServers, codecRegistry);
        this.netServer.start(new ServerTransportData(bindInfo.host(), bindInfo.port()), bindInfo.scheme());
    }

    public void stop() {
        netServer.stop();
    }

    public <T extends GMAPacket<T>> void broadcast(T packet) {
        netServer.broadcast(packet);
    }
}
