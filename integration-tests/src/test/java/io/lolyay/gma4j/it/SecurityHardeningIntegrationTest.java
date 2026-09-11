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
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.transport.TransportManager;
import io.lolyay.gma4j.net.transport.netty.NettyClientConnection;
import io.lolyay.gma4j.net.transport.netty.NettyClientTransportFactory;
import io.lolyay.gma4j.net.transport.ws.WsClientTransportFactory;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHardeningIntegrationTest {

    @BeforeAll
    static void prepareProtocol() {
        CodecRegistry.getInstance().addCodec(ClientGreetingPacket.TYPE);
        CodecRegistry.getInstance().addCodec(ServerWelcomePacket.TYPE);
        TransportManager.registerClientFactory(new NettyClientTransportFactory());
        TransportManager.registerClientFactory(new WsClientTransportFactory());
    }

    @Test
    void silentConnectionIsDroppedAtHandshakeDeadline() throws Exception {
        long oldTimeout = SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS;
        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = 700;
        URI uri = URI.create("gma4j://127.0.0.1:" + freePort());
        GMA4JServer server = new GMA4JServer(noopHandler(), hostKey());
        try {
            server.start(new ServerBindInfo(uri.getHost(), uri.getPort(), "gma4j", new GmaNoAuthServer()));
            try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
                socket.setSoTimeout(10_000);
                assertEquals(-1, socket.getInputStream().read(), "server must drop the silent connection");
            }
        } finally {
            SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = oldTimeout;
            assertDoesNotThrow(server::stop);
        }
    }

    @Test
    void admissionCapRejectsAndReleases() throws Exception {
        int oldCap = SharedConfig.MAX_CONNECTIONS;
        SharedConfig.MAX_CONNECTIONS = 1;
        URI uri = URI.create("gma4j://127.0.0.1:" + freePort());
        GMA4JServer server = new GMA4JServer(noopHandler(), hostKey());
        try {
            server.start(new ServerBindInfo(uri.getHost(), uri.getPort(), "gma4j", new GmaNoAuthServer()));
            Socket first = new Socket(uri.getHost(), uri.getPort());
            try {
                try (Socket second = new Socket(uri.getHost(), uri.getPort())) {
                    second.setSoTimeout(5_000);
                    assertEquals(-1, second.getInputStream().read(), "connection over the cap must be rejected");
                }
            } finally {
                first.close();
            }
            // the slot frees up once the admitted connection is gone
            Thread.sleep(500);
            try (Socket third = new Socket(uri.getHost(), uri.getPort())) {
                third.setSoTimeout(2_000);
                InputStream in = third.getInputStream();
                assertThrows(SocketTimeoutException.class, in::read, "freed slot must admit again");
            }
        } finally {
            SharedConfig.MAX_CONNECTIONS = oldCap;
            assertDoesNotThrow(server::stop);
        }
    }

    @Test
    void remoteDisconnectStopsClientTimersAndAllowsReconnect() throws Exception {
        URI uri = URI.create("gma4j://127.0.0.1:" + freePort());
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<ClientOnServer> serverSide = new AtomicReference<>();
        GMA4JServer server = new GMA4JServer(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                if (packet instanceof ClientGreetingPacket) {
                    received.countDown();
                    return true;
                }
                return false;
            }

            @Override
            public void onClientAuthenticated(ClientOnServer client) {
                serverSide.set(client);
            }
        }, hostKey());
        Handler handler = new Handler();
        GMA4JClient client = new GMA4JClient(handler);
        int baseline = schedulerThreadCount();
        try {
            server.start(new ServerBindInfo(uri.getHost(), uri.getPort(), "gma4j", new GmaNoAuthServer()));
            client.connect(new ClientConnectionInfo("cleanup-client", uri, ClientAuth.none()));
            assertTrue(handler.authenticated.await(20, TimeUnit.SECONDS), "no auth");
            assertNotNull(serverSide.get());

            serverSide.get().disconnect("kicked for test");
            assertTrue(handler.closed.await(20, TimeUnit.SECONDS), "client never saw the remote close");
            assertTrue(waitForSchedulerCount(baseline), "client scheduler must stop after a remote disconnect");

            // a replaced client must not leak the previous session either
            handler.reset();
            client.connect(new ClientConnectionInfo("cleanup-client", uri, ClientAuth.none()));
            assertTrue(handler.authenticated.await(20, TimeUnit.SECONDS), "no auth after reconnect");
            client.sendWithCompletion(new ClientGreetingPacket("hello", 1)).get(20, TimeUnit.SECONDS);
            assertTrue(received.await(20, TimeUnit.SECONDS), "server did not receive the packet");
        } finally {
            try {
                assertDoesNotThrow(client::disconnect);
            } finally {
                assertDoesNotThrow(server::stop);
            }
        }
        assertTrue(waitForSchedulerCount(baseline), "client scheduler must stop after disconnect");
    }

    @Test
    void nettySendCompletesAndEnforcesQueueCeiling() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel();
        NettyClientConnection connection = new NettyClientConnection(channel);

        CompletableFuture<Void> done = connection.sendWithCompletion(new byte[16], false);
        assertTrue(done.isDone() && !done.isCompletedExceptionally(), "write must complete");
        assertNotNull(channel.readOutbound(), "data must reach the channel");

        long oldCeiling = SharedConfig.MAX_QUEUED_WRITE_BYTES;
        SharedConfig.MAX_QUEUED_WRITE_BYTES = 8;
        try {
            CompletableFuture<Void> rejected = connection.sendWithCompletion(new byte[64], false);
            assertTrue(rejected.isCompletedExceptionally(), "over-ceiling write must be rejected");
            assertFalse(connection.send(new byte[64], false), "send must report the rejection");
        } finally {
            SharedConfig.MAX_QUEUED_WRITE_BYTES = oldCeiling;
            channel.finishAndReleaseAll();
        }
    }

    private static int schedulerThreadCount() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && "gma4j-client-scheduler".equals(thread.getName()))
                .count();
    }

    private static boolean waitForSchedulerCount(int target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (schedulerThreadCount() <= target) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static ServerEventHandler noopHandler() {
        return (client, packet) -> false;
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

    private static final class Handler implements ClientEventHandler {
        volatile CountDownLatch authenticated = new CountDownLatch(1);
        volatile CountDownLatch closed = new CountDownLatch(1);

        void reset() {
            authenticated = new CountDownLatch(1);
            closed = new CountDownLatch(1);
        }

        @Override
        public <T extends GMAPacket<T>> boolean handle(T packet) {
            return true;
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
        }

        @Override
        public void onAuthSuccess() {
            authenticated.countDown();
        }
    }
}
