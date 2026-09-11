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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.lolyay.gma4j.net.shared.SharedConfig;

@Slf4j
@RequiredArgsConstructor
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
    private final AtomicLong bigSizeBudgetUsed = new AtomicLong();
    @Getter(AccessLevel.NONE)
    private final AtomicInteger admittedConnections = new AtomicInteger();
    @Getter(AccessLevel.NONE)
    private volatile ScheduledExecutorService scheduler;

    private IServerTransport transport;

    public void start(ServerTransportData data, String scheme) {
        ServerTransportManager.register();
        IServerTransportFactory factory = TransportManager.serverFactoryFor(scheme);
        transport = factory.create(data, this);
        codecRegistry.warmup();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gma4j-server-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        transport.start();
        log.info("GMA4J server listening on {}:{} ({})", data.host(), data.port(), scheme);
    }

    public void stop() {
        if(transport != null) {
            transport.stop();
        }
        ScheduledExecutorService current = scheduler;
        if(current != null) {
            current.shutdownNow();
            scheduler = null;
        }
    }

    public boolean tryAdmit() {
        while (true) {
            int current = admittedConnections.get();
            if (current >= SharedConfig.MAX_CONNECTIONS) {
                return false;
            }
            if (admittedConnections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void releaseAdmission() {
        admittedConnections.decrementAndGet();
    }

    ScheduledFuture<?> scheduleHandshakeDeadline(Runnable task) {
        ScheduledExecutorService current = scheduler;
        if (current == null || current.isShutdown()) {
            return null;
        }
        return current.schedule(task, SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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

    /** Takes as much of wanted as the global budget still holds */
    public long reserveBigSizeBudget(long wanted) {
        while (true) {
            long used = bigSizeBudgetUsed.get();
            long take = Math.min(wanted, Math.max(0, SharedConfig.BIG_SIZE_TOTAL_BUDGET - used));
            if (take <= 0) {
                return 0;
            }
            if (bigSizeBudgetUsed.compareAndSet(used, used + take)) {
                return take;
            }
        }
    }

    public void releaseBigSizeBudget(long amount) {
        bigSizeBudgetUsed.addAndGet(-amount);
    }
}
