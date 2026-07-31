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
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalIntegrationTest {

    private static final int CLIENT_COUNT = 4;

    @Test
    void clientsHandshakeAuthAndExchangePackets() throws Exception {
        CodecRegistry.getInstance().addCodec(ClientGreetingPacket.TYPE);
        CodecRegistry.getInstance().addCodec(ServerWelcomePacket.TYPE);
        TransportManager.registerClientFactory(new NettyClientTransportFactory());

        URI uri = URI.create("gma4j://127.0.0.1:" + freePort());
        IServerCertificateProvider certProvider = hostKey();

        AtomicInteger greetingsSeen = new AtomicInteger();
        ServerEventHandler serverHandler = new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                if(packet instanceof ClientGreetingPacket greeting) {
                    greetingsSeen.incrementAndGet();
                    client.send(new ServerWelcomePacket(
                            "welcome " + greeting.clientName(),
                            client.getNetServer().getClientsById().size()));
                    return true;
                }
                return false;
            }
        };

        GMA4JServer server = new GMA4JServer(serverHandler, certProvider);
        server.start(new ServerBindInfo(uri.getHost(), uri.getPort(), new GmaNoAuthServer()));

        CountDownLatch welcomes = new CountDownLatch(CLIENT_COUNT);
        List<ServerWelcomePacket> received = new CopyOnWriteArrayList<>();
        List<GMA4JClient> clients = new ArrayList<>();

        try {
            for(int i = 0; i < CLIENT_COUNT; i++) {
                String name = "client-" + i;
                GreetingClient handler = new GreetingClient(name, received, welcomes);
                GMA4JClient client = new GMA4JClient(handler);
                handler.client = client;
                clients.add(client);
                client.connect(new ClientConnectionInfo(name, uri, ClientAuth.none()));
            }

            assertTrue(welcomes.await(20, TimeUnit.SECONDS), "every client should receive its welcome");
            assertEquals(CLIENT_COUNT, greetingsSeen.get(), "server should see one greeting per client");
            assertEquals(CLIENT_COUNT, received.size(), "each client should get exactly one welcome");
            for(ServerWelcomePacket welcome : received) {
                assertTrue(welcome.greeting().startsWith("welcome client-"), "unexpected greeting: " + welcome.greeting());
                assertTrue(welcome.connectedClients() >= 1);
            }
        } finally {
            clients.forEach(GMA4JClient::disconnect);
            server.stop();
        }
    }

    private static int freePort() throws Exception {
        try(ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static IServerCertificateProvider hostKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = kpg.generateKeyPair();
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
        private GMA4JClient client;

        GreetingClient(String name, List<ServerWelcomePacket> received, CountDownLatch welcomes) {
            this.name = name;
            this.received = received;
            this.welcomes = welcomes;
        }

        @Override
        public <T extends GMAPacket<T>> boolean handle(T packet) {
            if(packet instanceof ServerWelcomePacket welcome) {
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
        }

        @Override
        public void onConnectionError(Throwable e) {
        }
    }
}
