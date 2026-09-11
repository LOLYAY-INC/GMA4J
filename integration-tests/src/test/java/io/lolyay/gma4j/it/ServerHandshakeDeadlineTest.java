package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.ClientType;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.auth.server.GmaAuthServer;
import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.encryption.EncryptionMode;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.encryption.utils.DHUtil;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthPacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SAuthResponsePacket;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SHelloPacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CKeepAlivePacket;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import io.lolyay.gma4j.net.server.net.GMA4JNetServer;
import io.lolyay.gma4j.net.server.systemcodec.ServerDefaultSystemPacketCallback;
import io.lolyay.gma4j.net.shared.ENV;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerHandshakeDeadlineTest {
    private static final long ORIGINAL_HANDSHAKE_TIMEOUT_MS = SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS;

    private final List<GMA4JNetServer> servers = new ArrayList<>();

    @BeforeAll
    static void warmupCodec() {
        CodecRegistry.getInstance().warmup();
    }

    @AfterEach
    void cleanUp() {
        for(GMA4JNetServer server : servers) {
            server.stop();
        }
        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = ORIGINAL_HANDSHAKE_TIMEOUT_MS;
    }

    @Test
    void deadlineStartsBeforeConnectedCallbackReturns() throws Exception {
        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = 100;
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        GMA4JNetServer server = server(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }

            @Override
            public void onClientConnected(ClientOnServer client) {
                callbackEntered.countDown();
                await(releaseCallback);
            }
        }, new BlockingAuthServer());
        ClientOnServer client = new ClientOnServer(server, "blocked-callback");
        TestSender sender = new TestSender(SendBehavior.ACCEPT);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> connection = executor.submit(() -> client.onConnectionEstablished(sender));
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            assertTrue(sender.closed.await(5, TimeUnit.SECONDS));
            assertFalse(client.isConnected());
            releaseCallback.countDown();
            connection.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void authenticationCancelsDeadline() throws Exception {
        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = 100;
        GMA4JNetServer server = server(emptyHandler(), new BlockingAuthServer());
        ClientOnServer client = new ClientOnServer(server, "authenticated");
        TestSender sender = new TestSender(SendBehavior.ACCEPT);
        client.onConnectionEstablished(sender);
        client.setAssignedId(UUID.randomUUID());
        client.setClaimedClientId("authenticated");

        assertTrue(client.completeAuthentication());
        assertFalse(sender.closed.await(300, TimeUnit.MILLISECONDS));
        assertTrue(client.isConnected());
        assertTrue(client.isAuthenticated());
        assertEquals(client, server.getClient(client.getAssignedId()));
    }

    @Test
    void connectionCloseCancelsDeadlineAndNotifiesOnce() throws Exception {
        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = 100;
        AtomicInteger disconnectedCallbacks = new AtomicInteger();
        GMA4JNetServer server = server(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }

            @Override
            public void onClientDisconnected(ClientOnServer client, String reason) {
                disconnectedCallbacks.incrementAndGet();
            }
        }, new BlockingAuthServer());
        ClientOnServer client = new ClientOnServer(server, "closed");
        TestSender sender = new TestSender(SendBehavior.ACCEPT);
        client.onConnectionEstablished(sender);

        client.onConnectionClosed("controlled close");

        assertFalse(client.isConnected());
        assertEquals(1, sender.closeCount.get());
        assertEquals(1, disconnectedCallbacks.get());
        assertFalse(new CountDownLatch(1).await(300, TimeUnit.MILLISECONDS));
        assertEquals(1, sender.closeCount.get());
        assertEquals(1, disconnectedCallbacks.get());
    }

    @Test
    void stopClosesPendingAndAuthenticatedConnections() throws Exception {
        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = 10_000;
        GMA4JNetServer server = server(emptyHandler(), new BlockingAuthServer());
        ClientOnServer pending = new ClientOnServer(server, "pending");
        ClientOnServer authenticated = new ClientOnServer(server, "authenticated");
        TestSender pendingSender = new TestSender(SendBehavior.ACCEPT);
        TestSender authenticatedSender = new TestSender(SendBehavior.ACCEPT);
        pending.onConnectionEstablished(pendingSender);
        authenticated.onConnectionEstablished(authenticatedSender);
        authenticated.setAssignedId(UUID.randomUUID());
        authenticated.setClaimedClientId("authenticated");
        assertTrue(authenticated.completeAuthentication());

        server.stop();

        assertTrue(pendingSender.closed.await(1, TimeUnit.SECONDS));
        assertTrue(authenticatedSender.closed.await(1, TimeUnit.SECONDS));
        assertFalse(pending.isConnected());
        assertFalse(authenticated.isConnected());
        assertTrue(server.getClientsById().isEmpty());
        assertTrue(server.getClientsByClaimedId().isEmpty());
    }

    @Test
    void expiredCreateChallengeCannotAdvanceHandshake() throws Exception {
        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = 500;
        BlockingAuthServer authServer = new BlockingAuthServer();
        authServer.blockChallenge = true;
        GMA4JNetServer server = server(emptyHandler(), authServer);
        ClientOnServer client = new ClientOnServer(server, "slow-challenge");
        TestSender sender = new TestSender(SendBehavior.ACCEPT);
        client.onConnectionEstablished(sender);
        ServerDefaultSystemPacketCallback callback = beginHandshake(client);
        int sendsBeforeChallenge = sender.sendCount.get();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> auth = executor.submit(() -> callback.onSystemPacket(
                    new C2SAuthPacket(GmaAuthType.CUSTOM, new byte[0], "slow-challenge")));
            assertTrue(authServer.challengeEntered.await(5, TimeUnit.SECONDS));
            assertTrue(sender.closed.await(5, TimeUnit.SECONDS));
            authServer.releaseChallenge.countDown();
            auth.get(5, TimeUnit.SECONDS);

            assertFalse(client.isConnected());
            assertFalse(client.isAuthenticated());
            assertNull(client.getPendingChallenge());
            assertEquals(sendsBeforeChallenge, sender.sendCount.get());
            assertTrue(server.getClientsById().isEmpty());
        } finally {
            authServer.releaseChallenge.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void expiredVerificationCannotRegisterOrAuthenticateClient() throws Exception {
        SharedConfig.AUTH_HANDSHAKE_TIMEOUT_MS = 500;
        BlockingAuthServer authServer = new BlockingAuthServer();
        authServer.blockVerification = true;
        AtomicInteger authenticatedCallbacks = new AtomicInteger();
        GMA4JNetServer server = server(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }

            @Override
            public void onClientAuthenticated(ClientOnServer client) {
                authenticatedCallbacks.incrementAndGet();
            }
        }, authServer);
        ClientOnServer client = new ClientOnServer(server, "slow-verification");
        TestSender sender = new TestSender(SendBehavior.ACCEPT);
        client.onConnectionEstablished(sender);
        ServerDefaultSystemPacketCallback callback = beginHandshake(client);
        callback.onSystemPacket(new C2SAuthPacket(GmaAuthType.CUSTOM, new byte[0], "slow-verification"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> verification = executor.submit(() -> callback.onSystemPacket(
                    new C2SAuthResponsePacket(new byte[]{1})));
            assertTrue(authServer.verificationEntered.await(5, TimeUnit.SECONDS));
            assertTrue(sender.closed.await(5, TimeUnit.SECONDS));
            authServer.releaseVerification.countDown();
            verification.get(5, TimeUnit.SECONDS);

            assertFalse(client.isConnected());
            assertFalse(client.isAuthenticated());
            assertTrue(server.getClientsById().isEmpty());
            assertTrue(server.getClientsByClaimedId().isEmpty());
            assertEquals(0, authenticatedCallbacks.get());
        } finally {
            authServer.releaseVerification.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedOrThrowingSendClosesConnection() throws Exception {
        for(SendBehavior behavior : List.of(SendBehavior.REJECT, SendBehavior.THROW)) {
            GMA4JNetServer server = server(emptyHandler(), new BlockingAuthServer());
            ClientOnServer client = new ClientOnServer(server, behavior.name());
            TestSender sender = new TestSender(behavior);
            client.onConnectionEstablished(sender);

            client.send(new S2CKeepAlivePacket(1));

            assertTrue(sender.closed.await(1, TimeUnit.SECONDS));
            assertFalse(client.isConnected());
        }
    }

    private ServerDefaultSystemPacketCallback beginHandshake(ClientOnServer client) {
        ServerDefaultSystemPacketCallback callback = new ServerDefaultSystemPacketCallback(client);
        DHUtil.DhState dhState = DHUtil.createDhState();
        callback.onSystemPacket(new C2SHelloPacket(
                ENV.GMA4J_VERSION,
                "gma4j://deadline-test",
                ClientType.GMA4J_JAVA,
                ENV.SYSTEM_CODEC_VERSION,
                ENV.ENCRYPTION_CODEC_VERSION,
                CodecRegistry.getInstance().getConfig().globalCodecState(),
                0,
                0,
                List.of(EncryptionMode.GM_AES_V1),
                new byte[32],
                dhState.publicKey(),
                new byte[32],
                List.of(GmaAuthType.CUSTOM),
                new byte[0]
        ));
        assertTrue(client.isConnected());
        return callback;
    }

    private GMA4JNetServer server(ServerEventHandler eventHandler, GmaAuthServer authServer) throws Exception {
        GMA4JNetServer server = new GMA4JNetServer(
                eventHandler,
                hostKey(),
                Map.of(GmaAuthType.CUSTOM, authServer),
                CodecRegistry.getInstance()
        );
        servers.add(server);
        return server;
    }

    private static ServerEventHandler emptyHandler() {
        return new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }
        };
    }

    private static IServerCertificateProvider hostKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        return new IServerCertificateProvider() {
            @Override
            public byte[] getCertificate() {
                return pair.getPublic().getEncoded();
            }

            @Override
            public PrivateKey getSigningKey() {
                return pair.getPrivate();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private enum SendBehavior {
        ACCEPT,
        REJECT,
        THROW
    }

    private static final class TestSender implements MessageSender {
        private final SendBehavior behavior;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();

        private TestSender(SendBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public boolean send(byte[] data) {
            sendCount.incrementAndGet();
            if(behavior == SendBehavior.THROW) {
                throw new IllegalStateException("controlled send failure");
            }
            return behavior == SendBehavior.ACCEPT;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            closed.countDown();
        }
    }

    private static final class BlockingAuthServer implements GmaAuthServer {
        private final CountDownLatch challengeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseChallenge = new CountDownLatch(1);
        private final CountDownLatch verificationEntered = new CountDownLatch(1);
        private final CountDownLatch releaseVerification = new CountDownLatch(1);
        private volatile boolean blockChallenge;
        private volatile boolean blockVerification;

        @Override
        public byte[] createChallenge(UUID clientId, String claimedClientId, byte[] clientExtraData) {
            challengeEntered.countDown();
            if(blockChallenge) {
                await(releaseChallenge);
            }
            return new byte[]{1};
        }

        @Override
        public boolean verifyClientResponse(byte[] serverChallenge,
                                            byte[] response,
                                            UUID clientId,
                                            String claimedClientId,
                                            byte[] stateHash) {
            verificationEntered.countDown();
            if(blockVerification) {
                await(releaseVerification);
            }
            return true;
        }

        @Override
        public GmaAuthType authType() {
            return GmaAuthType.CUSTOM;
        }
    }
}
