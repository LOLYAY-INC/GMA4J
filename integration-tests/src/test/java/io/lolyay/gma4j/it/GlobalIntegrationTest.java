package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.client.ClientConnectionInfo;
import io.lolyay.gma4j.net.client.ClientEventHandler;
import io.lolyay.gma4j.net.client.GMA4JClient;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.client.ClientAuth;
import io.lolyay.gma4j.net.codec.auth.server.GmaNoAuthServer;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.server.GMA4JServer;
import io.lolyay.gma4j.net.server.ServerBindInfo;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import io.lolyay.gma4j.net.transport.TransportManager;
import io.lolyay.gma4j.net.transport.netty.NettyClientTransportFactory;
import io.lolyay.gma4j.net.transport.ws.WsClientTransportFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalIntegrationTest {

    private static final int CLIENT_COUNT = 4;

    @BeforeAll
    static void prepareProtocol() {
        CodecRegistry.getInstance().addCodec(ClientGreetingPacket.TYPE);
        CodecRegistry.getInstance().addCodec(ServerWelcomePacket.TYPE);
        TransportManager.registerClientFactory(new NettyClientTransportFactory());
        TransportManager.registerClientFactory(new WsClientTransportFactory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gma4j", "ws"})
    void clientsHandshakeAuthExchangeAndDisconnect(String scheme) throws Exception {
        URI uri = URI.create(scheme + "://127.0.0.1:" + freePort());
        AtomicInteger greetingsSeen = new AtomicInteger();
        CountDownLatch serverClosed = new CountDownLatch(CLIENT_COUNT);
        AtomicReference<Throwable> serverError = new AtomicReference<>();
        ServerEventHandler serverHandler = new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                if (packet instanceof ClientGreetingPacket greeting) {
                    greetingsSeen.incrementAndGet();
                    client.send(new ServerWelcomePacket(
                            "welcome " + greeting.clientName(),
                            client.getNetServer().getClientsById().size()));
                    return true;
                }
                return false;
            }

            @Override
            public void onClientDisconnected(ClientOnServer client, String reason) {
                serverClosed.countDown();
            }

            @Override
            public void onClientError(ClientOnServer client, Throwable e) {
                serverError.compareAndSet(null, e);
            }
        };

        GMA4JServer server = new GMA4JServer(serverHandler, hostKey());
        server.start(new ServerBindInfo(uri.getHost(), uri.getPort(), scheme, new GmaNoAuthServer()));
        CountDownLatch welcomes = new CountDownLatch(CLIENT_COUNT);
        CountDownLatch clientClosed = new CountDownLatch(CLIENT_COUNT);
        List<ServerWelcomePacket> received = new CopyOnWriteArrayList<>();
        List<Throwable> clientErrors = new CopyOnWriteArrayList<>();
        List<GMA4JClient> clients = new ArrayList<>();

        try {
            for (int i = 0; i < CLIENT_COUNT; i++) {
                String name = "client-" + i;
                GreetingClient handler = new GreetingClient(name, received, welcomes, clientClosed, clientErrors);
                GMA4JClient client = new GMA4JClient(handler);
                handler.client = client;
                clients.add(client);
                client.connect(new ClientConnectionInfo(name, uri, ClientAuth.none()));
            }

            assertTrue(welcomes.await(20, TimeUnit.SECONDS));
            assertEquals(CLIENT_COUNT, greetingsSeen.get());
            assertEquals(CLIENT_COUNT, received.size());
            assertEquals(CLIENT_COUNT, server.getNetServer().getClientsById().size());
            for (ServerWelcomePacket welcome : received) {
                assertTrue(welcome.greeting().startsWith("welcome client-"));
                assertTrue(welcome.connectedClients() >= 1);
            }

            clients.forEach(GMA4JClient::disconnect);
            assertTrue(clientClosed.await(10, TimeUnit.SECONDS));
            assertTrue(serverClosed.await(10, TimeUnit.SECONDS));
            assertTrue(server.getNetServer().getClientsById().isEmpty());
            assertTrue(server.getNetServer().getClientsByClaimedId().isEmpty());
            assertTrue(clientErrors.isEmpty(), () -> "Client error: " + clientErrors.getFirst());
            assertEquals(null, serverError.get());
            clients.forEach(client -> assertDoesNotThrow(client::disconnect));
        } finally {
            clients.forEach(GMA4JClient::disconnect);
            assertDoesNotThrow(server::stop);
        }
    }

    @Test
    void webSocketBindFailureIsReported() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            GMA4JServer server = new GMA4JServer(new ServerEventHandler() {
                @Override
                public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                    return false;
                }
            }, hostKey());
            assertThrows(IllegalStateException.class, () -> server.start(new ServerBindInfo(
                    "127.0.0.1", occupied.getLocalPort(), "ws", new GmaNoAuthServer())));
            assertDoesNotThrow(server::stop);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static IServerCertificateProvider hostKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        return new IServerCertificateProvider() {
            @Override
            public byte[] getCertificate() {
                return keyPair.getPublic().getEncoded();
            }

            @Override
            public PrivateKey getSigningKey() {
                return keyPair.getPrivate();
            }
        };
    }

    static final class GreetingClient implements ClientEventHandler {
        private final String name;
        private final List<ServerWelcomePacket> received;
        private final CountDownLatch welcomes;
        private final CountDownLatch closed;
        private final List<Throwable> errors;
        private GMA4JClient client;

        GreetingClient(String name, List<ServerWelcomePacket> received,
                       CountDownLatch welcomes, CountDownLatch closed,
                       List<Throwable> errors) {
            this.name = name;
            this.received = received;
            this.welcomes = welcomes;
            this.closed = closed;
            this.errors = errors;
        }

        @Override
        public <T extends GMAPacket<T>> boolean handle(T packet) {
            if (packet instanceof ServerWelcomePacket welcome) {
                received.add(welcome);
                welcomes.countDown();
                return true;
            }
            return false;
        }

        @Override
        public void onAuthSuccess() {
            client.send(new ClientGreetingPacket(name, 42));
        }

        @Override
        public void onConnectionEstablished() {
        }

        @Override
        public void onConnectionClosed(String reason) {
            closed.countDown();
        }

        @Override
        public void onConnectionError(Throwable e) {
            errors.add(e);
        }
    }
}
