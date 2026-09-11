package io.lolyay.gma4j.net.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.lolyay.gma4j.net.delivery.DeliveryTestSupport.sender;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableDeliverySessionTest {
    @TempDir
    Path tempDir;

    @Test
    void commitsOutboxBeforeSending() {
        Path path = tempDir.resolve("ordering");
        DeliveryLimits limits = limits(10, 1024, 4);
        try (H2DeliveryStore store = H2DeliveryStore.open(path, limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, sender(packet -> {
                try (H2DeliveryStore observer = H2DeliveryStore.open(path, limits)) {
                    assertEquals(1, new DurableDeliverySession("peer", observer).pendingOutboxCount());
                }
            }));
            session.enqueue(UUID.randomUUID(), new byte[]{1});
        }
    }

    @Test
    void commitsInboxBeforeSendingAck() {
        Path path = tempDir.resolve("ack-ordering");
        DeliveryLimits limits = limits(10, 1024, 4);
        try (H2DeliveryStore store = H2DeliveryStore.open(path, limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, sender(packet -> {
                try (H2DeliveryStore observer = H2DeliveryStore.open(path, limits)) {
                    DurableDeliverySession observed = new DurableDeliverySession("peer", observer);
                    assertEquals(1, observed.pollInbox(1).size());
                }
            }));
            session.handleTransfer(token,
                    new DeliveryTransferPacket(UUID.randomUUID(), new byte[]{1, 2}));
        }
    }

    @Test
    void fullStoreDoesNotAckNewTransfer() {
        DeliveryLimits limits = limits(1, 16, 4);
        List<Object> sent = new ArrayList<>();
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("full"), limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            session.enqueue(UUID.randomUUID(), new byte[]{1});
            Object token = new Object();
            session.attachAuthenticated(token, sent::add);
            sent.clear();

            assertThrows(DeliveryCapacityException.class, () -> session.handleTransfer(
                    token, new DeliveryTransferPacket(UUID.randomUUID(), new byte[]{2})));
            assertTrue(sent.isEmpty());
            assertEquals(1, session.pendingOutboxCount());
        }
    }

    @Test
    void lostAckProducesDuplicateAckWithoutDuplicateInboxItem() {
        List<DeliveryAckPacket> acknowledgements = new ArrayList<>();
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("lost-ack"), limits(10, 1024, 4))) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, sender(packet -> acknowledgements.add((DeliveryAckPacket) packet)));
            UUID id = UUID.randomUUID();
            DeliveryTransferPacket transfer = new DeliveryTransferPacket(id, new byte[]{3});

            assertTrue(session.handleTransfer(token, transfer));
            assertTrue(session.handleTransfer(token, transfer));

            assertEquals(2, acknowledgements.size());
            assertEquals(1, session.pollInbox(4).size());
            assertEquals(1, session.inboxRecordCount());
        }
    }

    @Test
    void staleConnectionCannotDetachOrAcknowledgeReplacement() {
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("tokens"), limits(10, 1024, 4))) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            UUID id = UUID.randomUUID();
            byte[] payload = {7};
            session.enqueue(id, payload);
            Object oldToken = new Object();
            Object currentToken = new Object();
            session.attachAuthenticated(oldToken, sender(packet -> { }));
            session.attachAuthenticated(currentToken, sender(packet -> { }));
            DeliveryAckPacket ack = new DeliveryAckPacket(id, DeliveryDigest.sha256(payload));

            assertFalse(session.detach(oldToken));
            assertFalse(session.handleAck(oldToken, ack));
            assertEquals(1, session.pendingOutboxCount());
            assertTrue(session.handleAck(currentToken, ack));
            assertEquals(0, session.pendingOutboxCount());
            assertTrue(session.detach(currentToken));
        }
    }

    @Test
    void peerNamespacesRemainSeparate() {
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("peers"), limits(10, 1024, 4))) {
            DurableDeliverySession first = new DurableDeliverySession("peer-a", store);
            DurableDeliverySession second = new DurableDeliverySession("peer-b", store);
            UUID id = UUID.randomUUID();
            first.enqueue(id, new byte[]{1});
            Object token = new Object();
            second.attachAuthenticated(token, sender(packet -> { }));

            assertTrue(second.handleAck(
                    token, new DeliveryAckPacket(id, DeliveryDigest.sha256(new byte[]{1}))));

            assertEquals(1, first.pendingOutboxCount());
            assertEquals(0, second.pendingOutboxCount());
        }
    }

    @Test
    void conflictingContentFailsClosedBeforeAck() {
        List<Object> sent = new ArrayList<>();
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("conflict"), limits(10, 1024, 4))) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, sent::add);
            UUID id = UUID.randomUUID();
            session.handleTransfer(token, new DeliveryTransferPacket(id, new byte[]{1}));
            sent.clear();

            assertThrows(DeliveryConflictException.class, () -> session.handleTransfer(
                    token, new DeliveryTransferPacket(id, new byte[]{2})));
            assertTrue(sent.isEmpty());
            assertArrayEquals(new byte[]{1}, session.pollInbox(1).getFirst().payload());
        }
    }

    @Test
    void replayCursorMakesProgressBeyondOneBatch() {
        DeliveryLimits limits = limits(20, 1024, 3);
        List<UUID> sent = new ArrayList<>();
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("batch"), limits)) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            List<UUID> ids = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                session.enqueue(id, new byte[0]);
            }
            session.attachAuthenticated(new Object(),
                    sender(packet -> sent.add(((DeliveryTransferPacket) packet).transferId())));
            assertEquals(ids.subList(0, 3), sent);

            session.retry();
            session.retry();

            assertEquals(ids, sent);
        }
    }

    @Test
    void transportCallbacksRunWithoutHoldingSessionMonitor() {
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("callback-lock"), limits(10, 1024, 4))) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            Object token = new Object();
            session.attachAuthenticated(token, sender(packet -> assertFalse(Thread.holdsLock(session))));
            session.enqueue(new byte[]{1});
            session.retry();
            session.handleTransfer(token, new DeliveryTransferPacket(UUID.randomUUID(), new byte[]{2}));
        }
    }

    @Test
    void replacementDuringReplayStopsOldBinding() {
        try (H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("replacement"), limits(10, 1024, 4))) {
            DurableDeliverySession session = new DurableDeliverySession("peer", store);
            session.enqueue(new byte[]{1});
            session.enqueue(new byte[]{2});
            Object oldToken = new Object();
            Object replacementToken = new Object();
            List<Object> oldSends = new ArrayList<>();
            List<Object> replacementSends = new ArrayList<>();
            session.attachAuthenticated(oldToken, sender(packet -> {
                oldSends.add(packet);
                session.attachAuthenticated(replacementToken, replacementSends::add);
            }));
            assertEquals(1, oldSends.size());
            assertEquals(2, replacementSends.size());
            assertFalse(session.detach(oldToken));
            assertTrue(session.isAttached());
        }
    }

    @Test
    void storageFailureDoesNotAckAndDetachesSession() {
        List<Object> sent = new ArrayList<>();
        H2DeliveryStore store = H2DeliveryStore.open(tempDir.resolve("closed-store"), limits(10, 1024, 4));
        DurableDeliverySession session = new DurableDeliverySession("peer", store);
        Object token = new Object();
        session.attachAuthenticated(token, sent::add);
        store.close();
        assertThrows(DeliveryException.class, () -> session.handleTransfer(token,
                new DeliveryTransferPacket(UUID.randomUUID(), new byte[]{1})));
        assertTrue(sent.isEmpty());
        assertFalse(session.isAttached());
    }

    private static DeliveryLimits limits(int records, long bytes, int batch) {
        return new DeliveryLimits(256, records, bytes, batch);
    }
}
