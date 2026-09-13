package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.delivery.data.DeliveryLimits;
import io.lolyay.gma4j.net.delivery.data.InboxItem;
import io.lolyay.gma4j.net.delivery.exception.DeliveryCapacityException;
import io.lolyay.gma4j.net.delivery.exception.DeliveryConflictException;
import io.lolyay.gma4j.net.delivery.exception.DeliveryException;
import io.lolyay.gma4j.net.delivery.packet.DeliveryAckPacket;
import io.lolyay.gma4j.net.delivery.packet.DeliveryTransferPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.lolyay.gma4j.net.delivery.DeliveryTestSupport.sender;
import static org.junit.jupiter.api.Assertions.*;

class H2DeliveryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void restartRecoversOutboxInboxAndTombstone() {
        Path path = tempDir.resolve("restart");
        DeliveryLimits limits = limits(10, 1024);
        UUID outboundId = UUID.randomUUID();
        UUID inboundId = UUID.randomUUID();
        try (H2DeliveryStore store = H2DeliveryStore.open(path, limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            session.enqueue(outboundId, new byte[]{1});
            Object token = new Object();
            session.attachAuthenticated(token, sender(packet -> { }));
            session.handleTransfer(token, new DeliveryTransferPacket(inboundId, new byte[]{2}));
            assertTrue(session.markProcessed(inboundId));
        }

        List<Object> replayed = new ArrayList<>();
        List<Object> receipts = new ArrayList<>();
        try (H2DeliveryStore store = H2DeliveryStore.open(path, limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, replayed::add);
            assertEquals(outboundId, ((DeliveryTransferPacket) replayed.getFirst()).transferId());
            assertTrue(session.pollInbox(4).isEmpty());
            assertEquals(1, session.inboxRecordCount());

            session.attachAuthenticated(token, receipts::add);
            session.handleTransfer(token, new DeliveryTransferPacket(inboundId, new byte[]{2}));
            assertInstanceOf(DeliveryAckPacket.class, receipts.getLast());
            assertTrue(session.pollInbox(4).isEmpty());
            assertEquals(1, session.inboxRecordCount());
        }
    }

    @Test
    void exactQuotasRejectWithoutDiscardingEvidence() {
        Path path = tempDir.resolve("quota");
        DeliveryLimits limits = new DeliveryLimits(2, 2, 4, 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (H2DeliveryStore store = H2DeliveryStore.open(path, limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            session.enqueue(first, new byte[]{1, 2});
            session.enqueue(second, new byte[]{3, 4});

            assertThrows(DeliveryCapacityException.class,
                    () -> session.enqueue(UUID.randomUUID(), new byte[0]));
            assertThrows(DeliveryCapacityException.class,
                    () -> session.enqueue(UUID.randomUUID(), new byte[]{5}));
            assertEquals(2, session.pendingOutboxCount());
        }

        try (H2DeliveryStore store = H2DeliveryStore.open(path, limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            assertEquals(2, session.pendingOutboxCount());
        }
    }

    @Test
    void processedTombstoneKeepsRecordQuota() {
        DeliveryLimits limits = new DeliveryLimits(8, 1, 8, 1);
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("tombstone-quota"), limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, sender(packet -> { }));
            UUID id = UUID.randomUUID();
            session.handleTransfer(token, new DeliveryTransferPacket(id, new byte[]{1}));
            assertTrue(session.markProcessed(id));

            assertThrows(DeliveryCapacityException.class, () -> session.enqueue(new byte[]{2}));
            assertEquals(1, session.inboxRecordCount());
            assertFalse(session.markProcessed(id));
        }
    }

    @Test
    void processedTombstonesAreBoundedAndOldestExpire() {
        // keep only the 2 most-recent tombstones so processed receipts cannot exhaust maxRecords
        DeliveryLimits limits = new DeliveryLimits(8, 10, 1024, 4, 2);
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("tombstone-bound"), limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, sender(packet -> { }));

            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                session.handleTransfer(token, new DeliveryTransferPacket(id, new byte[]{(byte) i}));
                assertTrue(session.markProcessed(id));
            }

            // compaction bounds record growth to the two newest tombstones
            assertEquals(2, session.inboxRecordCount());

            // a resend of a still-remembered transfer is deduplicated, no new record
            session.handleTransfer(token, new DeliveryTransferPacket(ids.get(4), new byte[]{4}));
            assertEquals(2, session.inboxRecordCount());
            assertTrue(session.pollInbox(4).isEmpty());

            // a resend of a compacted (expired) transfer is accepted again as fresh evidence
            session.handleTransfer(token, new DeliveryTransferPacket(ids.get(0), new byte[]{0}));
            assertEquals(3, session.inboxRecordCount());
            assertEquals(1, session.pollInbox(4).size());
        }
    }

    @Test
    void duplicateOutboundIdRequiresIdenticalContent() {
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("outbox-conflict"), limits(4, 64))) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            UUID id = UUID.randomUUID();
            session.enqueue(id, new byte[]{1});
            session.enqueue(id, new byte[]{1});

            assertThrows(DeliveryConflictException.class,
                    () -> session.enqueue(id, new byte[]{2}));
            assertEquals(1, session.pendingOutboxCount());
        }
    }

    @Test
    void twoStoreInstancesShareTransactionalQuotaLock() throws Exception {
        Path path = tempDir.resolve("concurrent");
        DeliveryLimits limits = new DeliveryLimits(8, 1, 16, 1);
        try (H2DeliveryStore firstStore = H2DeliveryStore.open(path, limits);
             H2DeliveryStore secondStore = H2DeliveryStore.open(path, limits);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            DurableDeliverySession first = new DurableDeliverySession("peer-a", firstStore);
            DurableDeliverySession second = new DurableDeliverySession("peer-b", secondStore);
            CountDownLatch start = new CountDownLatch(1);
            Future<Throwable> firstResult = executor.submit(() -> enqueueAfter(start, first));
            Future<Throwable> secondResult = executor.submit(() -> enqueueAfter(start, second));
            start.countDown();

            Throwable firstFailure = firstResult.get();
            Throwable secondFailure = secondResult.get();
            assertTrue((firstFailure == null) ^ (secondFailure == null));
            assertInstanceOf(DeliveryCapacityException.class,
                    firstFailure == null ? secondFailure : firstFailure);
            assertEquals(1, first.pendingOutboxCount() + second.pendingOutboxCount());
        }
    }

    @Test
    void corruptedEvidenceFailsOpenWithoutReset() throws Exception {
        Path path = tempDir.resolve("corrupt");
        DeliveryLimits limits = limits(4, 64);
        UUID id = UUID.randomUUID();
        try (H2DeliveryStore store = H2DeliveryStore.open(path, limits)) {
            new DurableDeliverySession("peer", store).enqueue(id, new byte[]{1});
        }
        String url = jdbcUrl(path);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE GMA4J_DELIVERY_OUTBOX SET DIGEST = ? WHERE TRANSFER_ID = ?")) {
            statement.setBytes(1, new byte[32]);
            statement.setObject(2, id);
            assertEquals(1, statement.executeUpdate());
        }

        assertThrows(DeliveryException.class, () -> H2DeliveryStore.open(path, limits));
        assertThrows(DeliveryException.class, () -> H2DeliveryStore.open(path, limits));
    }

    @Test
    void inboxReadsAndRecordPayloadsAreDefensiveCopies() {
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("copies"), limits(4, 64))) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, sender(packet -> { }));
            session.handleTransfer(token,
                    new DeliveryTransferPacket(UUID.randomUUID(), new byte[]{1, 2}));
            InboxItem item = session.pollInbox(1).getFirst();
            byte[] changed = item.payload();
            changed[0] = 9;

            assertArrayEquals(new byte[]{1, 2}, session.pollInbox(1).getFirst().payload());
        }
    }

    private static Throwable enqueueAfter(CountDownLatch start, DurableDeliverySession session) {
        try {
            start.await();
            session.enqueue(new byte[]{1});
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static DeliveryLimits limits(int records, long bytes) {
        return new DeliveryLimits(256, records, bytes, 4);
    }

    private static String jdbcUrl(Path path) {
        return "jdbc:h2:file:" + path.toAbsolutePath().normalize().toString().replace('\\', '/')
                + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE";
    }
}
