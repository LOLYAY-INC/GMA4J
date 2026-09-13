package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.client.ClientConnectionInfo;
import io.lolyay.gma4j.net.client.ClientEventHandler;
import io.lolyay.gma4j.net.client.GMA4JClient;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.client.ClientAuth;
import io.lolyay.gma4j.net.codec.auth.server.GmaApiHmacAuthServer;
import io.lolyay.gma4j.net.codec.encryption.client.IClientKnownCertificateKeeper;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.delivery.DeliveryPackets;
import io.lolyay.gma4j.net.delivery.DurableDeliverySession;
import io.lolyay.gma4j.net.delivery.H2DeliveryStore;
import io.lolyay.gma4j.net.delivery.data.DeliveryLimits;
import io.lolyay.gma4j.net.delivery.data.InboxItem;
import io.lolyay.gma4j.net.delivery.packet.DeliveryAckPacket;
import io.lolyay.gma4j.net.delivery.packet.DeliveryTransferPacket;
import io.lolyay.gma4j.net.server.GMA4JServer;
import io.lolyay.gma4j.net.server.ServerBindInfo;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import io.lolyay.gma4j.net.transport.TransportManager;
import io.lolyay.gma4j.net.transport.netty.NettyClientTransportFactory;
import io.lolyay.gma4j.net.transport.ws.WsClientTransportFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DurableDeliveryIntegrationTest {
    private static final String CLIENT_ID = "evidence-client";
    private static final String AUTH_SECRET = "local-delivery-test-key";

    @TempDir
    Path directory;

    @BeforeAll
    static void prepareProtocol() {
        DeliveryPackets.register(CodecRegistry.getInstance());
        TransportManager.registerClientFactory(new NettyClientTransportFactory());
        TransportManager.registerClientFactory(new WsClientTransportFactory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gma4j", "ws"})
    void replaysLostReceiptAfterRestartWithoutRedeliveringEvidence(String scheme) throws Exception {
        Path clientPath = directory.resolve("client");
        Path serverPath = directory.resolve("server");
        HostKey key = hostKey();
        byte[] evidence = "durable evidence".getBytes(StandardCharsets.UTF_8);
        byte[] response = "stored response".getBytes(StandardCharsets.UTF_8);
        UUID transferId;
        UUID responseId;

        try (H2DeliveryStore clientStore = H2DeliveryStore.open(clientPath, DeliveryLimits.defaults());
             H2DeliveryStore serverStore = H2DeliveryStore.open(serverPath, DeliveryLimits.defaults())) {
            DurableDeliverySession clientDelivery = new DurableDeliverySession("pinned-server", clientStore);
            DurableDeliverySession serverDelivery = new DurableDeliverySession(CLIENT_ID, serverStore);
            transferId = clientDelivery.enqueue(evidence);
            responseId = serverDelivery.enqueue(response);
            assertEquals(1, clientDelivery.pendingOutboxCount());
            assertEquals(1, serverDelivery.pendingOutboxCount());

            try (Link link = new Link(scheme, key, clientDelivery, serverDelivery, true)) {
                await(() -> link.ignoredAcks.get() > 0);
                await(() -> serverDelivery.pendingOutboxCount() == 0);
                assertInbox(serverDelivery, transferId, evidence);
                assertInbox(clientDelivery, responseId, response);
                assertEquals(1, clientDelivery.pendingOutboxCount());
                link.assertHealthy();
            }
        }

        try (H2DeliveryStore clientStore = H2DeliveryStore.open(clientPath, DeliveryLimits.defaults());
             H2DeliveryStore serverStore = H2DeliveryStore.open(serverPath, DeliveryLimits.defaults())) {
            DurableDeliverySession clientDelivery = new DurableDeliverySession("pinned-server", clientStore);
            DurableDeliverySession serverDelivery = new DurableDeliverySession(CLIENT_ID, serverStore);
            assertEquals(1, clientDelivery.pendingOutboxCount());
            assertInbox(serverDelivery, transferId, evidence);
            assertInbox(clientDelivery, responseId, response);

            try (Link link = new Link(scheme, key, clientDelivery, serverDelivery, false)) {
                await(() -> clientDelivery.pendingOutboxCount() == 0);
                assertInbox(serverDelivery, transferId, evidence);
                assertEquals(1, serverDelivery.inboxRecordCount());
                assertTrue(serverDelivery.markProcessed(transferId));
                clientDelivery.enqueue(transferId, evidence);
                await(() -> clientDelivery.pendingOutboxCount() == 0);
                assertTrue(serverDelivery.pollInbox(1).isEmpty());
                assertEquals(1, serverDelivery.inboxRecordCount());
                link.assertHealthy();
            }
        }

        try (H2DeliveryStore serverStore = H2DeliveryStore.open(serverPath, DeliveryLimits.defaults())) {
            DurableDeliverySession serverDelivery = new DurableDeliverySession(CLIENT_ID, serverStore);
            assertTrue(serverDelivery.pollInbox(1).isEmpty());
            assertEquals(1, serverDelivery.inboxRecordCount());
        }
    }

    private static void assertInbox(DurableDeliverySession delivery, UUID id, byte[] payload) {
        List<InboxItem> items = delivery.pollInbox(2);
        assertEquals(1, items.size());
        assertEquals(id, items.getFirst().transferId());
        assertArrayEquals(payload, items.getFirst().payload());
    }

    private static final class Link implements AutoCloseable {
        private final Object clientToken = new Object();
        private final DurableDeliverySession clientDelivery;
        private final GMA4JClient client;
        private final GMA4JServer server;
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private final AtomicInteger ignoredAcks = new AtomicInteger();

        private Link(String scheme, HostKey key, DurableDeliverySession clientDelivery,
                     DurableDeliverySession serverDelivery, boolean ignoreClientAcks) throws Exception {
            this.clientDelivery = clientDelivery;
            URI uri = URI.create(scheme + "://127.0.0.1:" + freePort());
            server = new GMA4JServer(new ServerEventHandler() {
                @Override
                public boolean handle(ClientOnServer connection, GMAPacket<?> packet) {
                    if (packet instanceof DeliveryTransferPacket transfer) {
                        return serverDelivery.handleTransfer(connection, transfer);
                    }
                    if (packet instanceof DeliveryAckPacket ack) {
                        return serverDelivery.handleAck(connection, ack);
                    }
                    return false;
                }

                @Override
                public void onClientAuthenticated(ClientOnServer connection) {
                    serverDelivery.attachAuthenticated(connection, connection::send);
                }

                @Override
                public void onClientDisconnected(ClientOnServer connection, String reason) {
                    serverDelivery.detach(connection);
                }

                @Override
                public void onClientError(ClientOnServer connection, Throwable error) {
                    errors.add(error);
                }
            }, key);
            client = new GMA4JClient(new ClientEventHandler() {
                @Override
                public <T extends GMAPacket<T>> boolean handle(T packet) {
                    if (packet instanceof DeliveryTransferPacket transfer) {
                        return clientDelivery.handleTransfer(clientToken, transfer);
                    }
                    if (packet instanceof DeliveryAckPacket ack) {
                        if (ignoreClientAcks) {
                            ignoredAcks.incrementAndGet();
                            return true;
                        }
                        return clientDelivery.handleAck(clientToken, ack);
                    }
                    return false;
                }

                @Override
                public void onAuthSuccess() {
                    clientDelivery.attachAuthenticated(clientToken, client::send);
                }

                @Override
                public void onConnectionEstablished() {
                }

                @Override
                public void onConnectionClosed(String reason) {
                    clientDelivery.detach(clientToken);
                }

                @Override
                public void onConnectionError(Throwable error) {
                    clientDelivery.detach(clientToken);
                    errors.add(error);
                }
            });
            client.setKnownCertificateKeeper(key);
            try {
                server.start(new ServerBindInfo(uri.getHost(), uri.getPort(), scheme,
                        new GmaApiHmacAuthServer(AUTH_SECRET)));
                client.connect(new ClientConnectionInfo(CLIENT_ID, uri, ClientAuth.hmac(AUTH_SECRET)));
                await(() -> clientDelivery.isAttached() && serverDelivery.isAttached());
            } catch (Exception | AssertionError failure) {
                close();
                throw failure;
            }
        }

        private void assertHealthy() {
            assertTrue(errors.isEmpty(), () -> "Connection errors: " + errors);
        }

        @Override
        public void close() {
            clientDelivery.detach(clientToken);
            try {
                if (client.getNetClient() != null) {
                    client.disconnect();
                }
            } finally {
                server.stop();
            }
        }
    }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.test() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.test(), "Delivery condition did not complete");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static HostKey hostKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return new HostKey(generator.generateKeyPair());
    }

    private interface Condition {
        boolean test() throws Exception;
    }

    private record HostKey(KeyPair pair) implements IServerCertificateProvider, IClientKnownCertificateKeeper {
        @Override
        public byte[] getCertificate() {
            return pair.getPublic().getEncoded();
        }

        @Override
        public PrivateKey getSigningKey() {
            return pair.getPrivate();
        }

        @Override
        public byte[] getKnownCertificateHashForUri(String uri) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(getCertificate());
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public boolean hasCertificateChangedForUri(String uri, byte[] certificate) {
            return !MessageDigest.isEqual(getCertificate(), certificate);
        }
    }
}
