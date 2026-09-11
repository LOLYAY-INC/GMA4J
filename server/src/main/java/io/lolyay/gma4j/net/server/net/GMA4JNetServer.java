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
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.transport.IServerTransport;
import io.lolyay.gma4j.net.transport.IServerTransportFactory;
import io.lolyay.gma4j.net.transport.ServerTransportData;
import io.lolyay.gma4j.net.transport.TransportManager;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Getter
public class GMA4JNetServer implements ServerClientHandler {
    private final ServerEventHandler eventHandler;
    private final IServerCertificateProvider certificateProvider;
    private final Map<GmaAuthType, GmaAuthServer> authServers;
    private final CodecRegistry codecRegistry;

    @Getter(AccessLevel.NONE)
    private final Map<UUID, ClientOnServer> clientsById = new ConcurrentHashMap<>();
    @Getter(AccessLevel.NONE)
    private final Map<String, ClientOnServer> clientsByClaimedId = new ConcurrentHashMap<>();
    @Getter(AccessLevel.NONE)
    private final Set<ClientOnServer> connections = ConcurrentHashMap.newKeySet();
    @Getter(AccessLevel.NONE)
    private final Object lifecycleMonitor = new Object();

    @Getter(AccessLevel.NONE)
    private ScheduledExecutorService handshakeScheduler;
    @Getter(AccessLevel.NONE)
    private volatile boolean acceptingConnections = true;
    private volatile IServerTransport transport;

    public GMA4JNetServer(ServerEventHandler eventHandler,
                          IServerCertificateProvider certificateProvider,
                          Map<GmaAuthType, GmaAuthServer> authServers,
                          CodecRegistry codecRegistry) {
        this.eventHandler = eventHandler;
        this.certificateProvider = certificateProvider;
        this.authServers = authServers;
        this.codecRegistry = codecRegistry;
        this.handshakeScheduler = createHandshakeScheduler();
    }

    public void start(ServerTransportData data, String scheme) {
        ServerTransportManager.register();
        IServerTransportFactory factory = TransportManager.serverFactoryFor(scheme);
        IServerTransport newTransport;
        synchronized (lifecycleMonitor) {
            if (handshakeScheduler.isShutdown()) {
                handshakeScheduler = createHandshakeScheduler();
            }
            acceptingConnections = true;
            newTransport = factory.create(data, this);
            transport = newTransport;
        }
        codecRegistry.warmup();
        newTransport.start();
        log.info("GMA4J server listening on {}:{} ({})", data.host(), data.port(), scheme);
    }

    public void stop() {
        IServerTransport currentTransport;
        synchronized (lifecycleMonitor) {
            acceptingConnections = false;
            currentTransport = transport;
            transport = null;
            handshakeScheduler.shutdownNow();
        }

        closeConnections("Server stopped");
        try {
            if(currentTransport != null) {
                currentTransport.stop();
            }
        } finally {
            closeConnections("Server stopped");
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

    void trackConnection(ClientOnServer client) {
        connections.add(client);
        ScheduledFuture<?> deadline = null;
        synchronized (lifecycleMonitor) {
            if(acceptingConnections && !handshakeScheduler.isShutdown()) {
                deadline = handshakeScheduler.schedule(
                        client::expireHandshake,
                        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                );
            }
        }

        if(deadline == null) {
            connections.remove(client);
            client.disconnect("Server is stopped");
            return;
        }
        client.installHandshakeDeadline(deadline);
        if (!client.isConnected()) {
            connections.remove(client);
        }
    }

    void untrackConnection(ClientOnServer client) {
        connections.remove(client);
    }

    public synchronized UUID registerClient(ClientOnServer client) {
        String claimedClientId = client.getClaimedClientId();
        UUID assignedId = client.getAssignedId();
        if (clientsByClaimedId.putIfAbsent(claimedClientId, client) != null) {
            return null;
        }
        if (clientsById.putIfAbsent(assignedId, client) != null) {
            clientsByClaimedId.remove(claimedClientId, client);
            return null;
        }
        return assignedId;
    }

    public UUID generateFreeUUID(String clientName) {
        UUID id = UUID.randomUUID();
        while(clientsById.get(id) != null) {
            id = UUID.randomUUID();
        }
        return id;
    }

    public synchronized void removeClient(ClientOnServer client) {
        if(client.getAssignedId() != null) {
            clientsById.remove(client.getAssignedId(), client);
        }
        if(client.getClaimedClientId() != null) {
            clientsByClaimedId.remove(client.getClaimedClientId(), client);
        }
    }

    public Map<UUID, ClientOnServer> getClientsById() {
        return Collections.unmodifiableMap(clientsById);
    }

    public Map<String, ClientOnServer> getClientsByClaimedId() {
        return Collections.unmodifiableMap(clientsByClaimedId);
    }

    public ClientOnServer getClient(UUID id) {
        return clientsById.get(id);
    }

    public <T extends GMAPacket<T>> void broadcast(T packet) {
        for(ClientOnServer client : clientsById.values()) {
            client.send(packet);
        }
    }

    private void closeConnections(String reason) {
        for(ClientOnServer client : List.copyOf(connections)) {
            try {
                client.disconnect(reason);
            } catch (RuntimeException exception) {
                log.warn("Failed to close connection {}", client.getRemoteId(), exception);
            }
        }
    }

    private static ScheduledExecutorService createHandshakeScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "gma4j-server-handshake-deadline");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
