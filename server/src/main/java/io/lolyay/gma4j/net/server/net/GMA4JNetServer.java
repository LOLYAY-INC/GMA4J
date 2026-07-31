package io.lolyay.gma4j.net.server.net;

import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;
import io.lolyay.gma4j.net.codec.connection.server.ServerClientHandler;
import io.lolyay.gma4j.net.codec.connection.server.ServerConnectionListener;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.transport.ServerTransportManager;
import io.lolyay.gma4j.net.transport.IServerTransport;
import io.lolyay.gma4j.net.transport.IServerTransportFactory;
import io.lolyay.gma4j.net.transport.ServerTransportData;
import io.lolyay.gma4j.net.transport.TransportManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
@Getter
public class GMA4JNetServer implements ServerClientHandler {
    private final ServerEventHandler eventHandler;
    private final IServerCertificateProvider certificateProvider;
    private final Map<GmaAuthType, GmaAuthServer> authServers;
    private final CodecRegistry codecRegistry;

    private final Map<UUID, ClientOnServer> clientsById = new ConcurrentHashMap<>();
    private final Map<String, ClientOnServer> clientsByClaimedId = new ConcurrentHashMap<>();

    private IServerTransport transport;

    public void start(ServerTransportData data, String scheme) {
        ServerTransportManager.register();
        IServerTransportFactory factory = TransportManager.serverFactoryFor(scheme);
        transport = factory.create(data, this);
        codecRegistry.warmup();
        transport.start();
        log.info("GMA4J server listening on {}:{} ({})", data.host(), data.port(), scheme);
    }

    public void stop() {
        if(transport != null) {
            transport.stop();
        }
    }

    @Override
    public ServerConnectionListener getOrCreateClient(String remoteIdentification) {
        return new ClientOnServer(this, remoteIdentification);
    }

    public List<GmaAuthType> getSupportedAuthTypes() {
        return new ArrayList<>(authServers.keySet());
    }

    public GmaAuthServer getAuthServer(GmaAuthType type) {
        return authServers.get(type);
    }

    public UUID registerClient(ClientOnServer client, String claimedClientId) {
        if(clientsByClaimedId.putIfAbsent(claimedClientId, client) != null) {
            return null;
        }
        UUID id;
        do {
            id = UUID.randomUUID();
        } while(clientsById.putIfAbsent(id, client) != null);
        return id;
    }

    public void removeClient(ClientOnServer client) {
        if(client.getAssignedId() != null) {
            clientsById.remove(client.getAssignedId(), client);
        }
        if(client.getClaimedClientId() != null) {
            clientsByClaimedId.remove(client.getClaimedClientId(), client);
        }
    }

    public ClientOnServer getClient(UUID id) {
        return clientsById.get(id);
    }

    public <T extends GMAPacket<T>> void broadcast(T packet) {
        for(ClientOnServer client : clientsById.values()) {
            client.send(packet);
        }
    }
}
