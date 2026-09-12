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

import java.net.ServerSocket;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionModesIntegrationTest {

    private static final int BIG_REQUEST = 3 * 1024 * 1024;
    private static final int BIG_PAYLOAD = 2 * 1024 * 1024;

    @BeforeAll
    static void prepareProtocol() {
        CodecRegistry.getInstance().addCodec(ClientGreetingPacket.TYPE);
        CodecRegistry.getInstance().addCodec(ServerWelcomePacket.TYPE);
        TransportManager.registerClientFactory(new NettyClientTransportFactory());
        TransportManager.registerClientFactory(new WsClientTransportFactory());
    }

    @Test
    void bigUrgentPacketRoundTripsAfterGrant() throws Exception {
        bigUrgentPacketRoundTripsAfterGrant("gma4j");
    }

    @Test
    void webSocketHonorsBigSizeGrant() throws Exception {
        bigUrgentPacketRoundTripsAfterGrant("ws");
    }

    private void bigUrgentPacketRoundTripsAfterGrant(String scheme) throws Exception {
        URI uri = URI.create(scheme + "://127.0.0.1:" + freePort());
        String bigName = "x".repeat(BIG_PAYLOAD);
        CountDownLatch echoed = new CountDownLatch(1);
        AtomicReference<ClientGreetingPacket> echo = new AtomicReference<>();
        CountDownLatch serverGrant = new CountDownLatch(1);

        ServerEventHandler serverHandler = new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                if (packet instanceof ClientGreetingPacket greeting) {
                    client.send(greeting);
                    return true;
                }
                return false;
            }

            @Override
            public void onClientModeChanged(ClientOnServer client) {
                serverGrant.countDown();
            }
        };

        GMA4JServer server = new GMA4JServer(serverHandler, hostKey());
        server.start(new ServerBindInfo(uri.getHost(), uri.getPort(), scheme, new GmaNoAuthServer()));
        ModeClient handler = new ModeClient(true, true, BIG_REQUEST, bigName, echo, echoed);
        GMA4JClient client = new GMA4JClient(handler);
        handler.client = client;

        try {
            client.connect(new ClientConnectionInfo("modes-client", uri, ClientAuth.none()));

            assertTrue(handler.granted.await(20, TimeUnit.SECONDS), "no mode grant");
            assertTrue(handler.grantedLowLatency);
            assertTrue(handler.grantedBigSize);
            assertEquals(BIG_REQUEST, handler.grantedMaxPacketSize);

            assertTrue(echoed.await(20, TimeUnit.SECONDS), "big packet was not echoed");
            assertNotNull(echo.get());
            assertEquals(bigName, echo.get().clientName());
            assertTrue(serverGrant.await(5, TimeUnit.SECONDS));
            assertTrue(handler.errors.get() == null, () -> "Client error: " + handler.errors.get());
        } finally {
            try {
                assertDoesNotThrow(client::disconnect);
            } finally {
                assertDoesNotThrow(server::stop);
            }
        }
    }

    @Test
    void modeRequestFloodDisconnects() throws Exception {
        URI uri = URI.create("gma4j://127.0.0.1:" + freePort());
        CountDownLatch serverDropped = new CountDownLatch(1);
        GMA4JServer server = new GMA4JServer(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }

            @Override
            public void onClientDisconnected(ClientOnServer client, String reason) {
                serverDropped.countDown();
            }
        }, hostKey());
        server.start(new ServerBindInfo(uri.getHost(), uri.getPort(), "gma4j", new GmaNoAuthServer()));
        ModeClient handler = new ModeClient(false, false, 0, null, null, null);
        GMA4JClient client = new GMA4JClient(handler);
        handler.client = client;

        try {
            client.connect(new ClientConnectionInfo("flood-client", uri, ClientAuth.none()));
            assertTrue(handler.granted.await(20, TimeUnit.SECONDS), "no mode grant");

            for (int i = 0; i < 8; i++) {
                try {
                    client.requestModes(true, false, 0);
                } catch (Exception ignored) {
                    break; // pipeline may already be closed by the disconnect
                }
            }
            assertTrue(serverDropped.await(20, TimeUnit.SECONDS), "flood did not disconnect");
        } finally {
            try {
                assertDoesNotThrow(client::disconnect);
            } finally {
                assertDoesNotThrow(server::stop);
            }
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

    static final class ModeClient implements ClientEventHandler {
        private final boolean requestLowLatency;
        private final boolean requestBigSize;
        private final int requestSize;
        private final String bigName;
        private final AtomicReference<ClientGreetingPacket> echo;
        private final CountDownLatch echoed;
        final CountDownLatch granted = new CountDownLatch(1);
        final AtomicReference<Throwable> errors = new AtomicReference<>();
        volatile boolean grantedLowLatency;
        volatile boolean grantedBigSize;
        volatile int grantedMaxPacketSize;
        GMA4JClient client;

        ModeClient(boolean requestLowLatency, boolean requestBigSize, int requestSize,
                   String bigName, AtomicReference<ClientGreetingPacket> echo, CountDownLatch echoed) {
            this.requestLowLatency = requestLowLatency;
            this.requestBigSize = requestBigSize;
            this.requestSize = requestSize;
            this.bigName = bigName;
            this.echo = echo;
            this.echoed = echoed;
        }

        @Override
        public <T extends GMAPacket<T>> boolean handle(T packet) {
            if (packet instanceof ClientGreetingPacket greeting && echo != null) {
                echo.set(greeting);
                echoed.countDown();
                return true;
            }
            return false;
        }

        @Override
        public void onAuthSuccess() {
            client.requestModes(requestLowLatency, requestBigSize, requestSize);
        }

        @Override
        public void onModesChanged(boolean lowLatency, boolean bigSize, int maxPacketSize) {
            grantedLowLatency = lowLatency;
            grantedBigSize = bigSize;
            grantedMaxPacketSize = maxPacketSize;
            granted.countDown();
            if (bigName != null) {
                client.sendUrgent(new ClientGreetingPacket(bigName, 1));
            }
        }

        @Override
        public void onConnectionEstablished() {
        }

        @Override
        public void onConnectionClosed(String reason) {
        }

        @Override
        public void onConnectionError(Throwable e) {
            errors.compareAndSet(null, e);
        }
    }
}
