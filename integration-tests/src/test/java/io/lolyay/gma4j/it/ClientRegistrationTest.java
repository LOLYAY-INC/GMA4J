package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CKeepAlivePacket;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import io.lolyay.gma4j.net.server.net.GMA4JNetServer;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientRegistrationTest {

    @Test
    void duplicateClaimedIdLeavesAcceptedIndexesUntouched() throws Exception {
        GMA4JNetServer server = server();
        ClientOnServer accepted = client(server, "claimed", UUID.randomUUID());
        ClientOnServer rejected = client(server, "claimed", UUID.randomUUID());

        assertEquals(accepted.getAssignedId(), server.registerClient(accepted));
        assertNull(server.registerClient(rejected));
        assertSame(accepted, server.getClientsByClaimedId().get("claimed"));
        assertSame(accepted, server.getClient(accepted.getAssignedId()));
        assertNull(server.getClient(rejected.getAssignedId()));
    }

    @Test
    void duplicateAssignedIdDoesNotReplaceAcceptedClient() throws Exception {
        GMA4JNetServer server = server();
        UUID assignedId = UUID.randomUUID();
        ClientOnServer accepted = client(server, "first", assignedId);
        ClientOnServer rejected = client(server, "second", assignedId);

        server.registerClient(accepted);
        assertNull(server.registerClient(rejected));
        assertSame(accepted, server.getClient(assignedId));
        assertNull(server.getClientsByClaimedId().get("second"));
    }

    @Test
    void concurrentRegistrationHasOneConsistentWinner() throws Exception {
        GMA4JNetServer server = server();
        int attempts = 32;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                ClientOnServer client = client(server, "shared", UUID.randomUUID());
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (server.registerClient(client) != null) {
                        accepted.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        } finally {
            executor.shutdown();
            assertEquals(true, executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertEquals(1, accepted.get());
        assertEquals(1, server.getClientsByClaimedId().size());
        assertEquals(1, server.getClientsById().size());
        assertSame(server.getClientsByClaimedId().get("shared"),
                server.getClientsById().values().iterator().next());
    }

    @Test
    void concurrentServerSendsPreserveSequenceOrder() throws Exception {
        GMA4JNetServer server = server();
        CodecRegistry.getInstance().warmup();
        ClientOnServer client = client(server, "client", UUID.randomUUID());
        CapturingSender sender = new CapturingSender();
        client.onConnectionEstablished(sender);
        int workers = 8;
        int sendsPerWorker = 50;
        int sends = workers * sendsPerWorker;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CyclicBarrier start = new CyclicBarrier(workers);
        try {
            for (int worker = 0; worker < workers; worker++) {
                int offset = worker * sendsPerWorker;
                executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < sendsPerWorker; i++) {
                        client.send(new S2CKeepAlivePacket(offset + i));
                    }
                    return null;
                });
            }
        } finally {
            executor.shutdown();
            assertEquals(true, executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertEquals(sends, sender.frames.size());
        for (int i = 0; i < sends; i++) {
            assertEquals(i, io.lolyay.gma4j.net.util.ByteReader.readInt(sender.frames.get(i), 0));
        }
    }

    private static final class CapturingSender implements MessageSender {
        private final CopyOnWriteArrayList<byte[]> frames = new CopyOnWriteArrayList<>();

        @Override
        public boolean send(byte[] data) {
            frames.add(data);
            return true;
        }

        @Override
        public void close() {
        }
    }

    private static ClientOnServer client(GMA4JNetServer server, String claimedId, UUID assignedId) {
        ClientOnServer client = new ClientOnServer(server, claimedId);
        client.setClaimedClientId(claimedId);
        client.setAssignedId(assignedId);
        return client;
    }

    private static GMA4JNetServer server() throws Exception {
        return new GMA4JNetServer(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }
        }, hostKey(), Map.of(), CodecRegistry.getInstance());
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
}
