package io.lolyay.gma4j.net.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.lolyay.gma4j.net.delivery.DeliveryTestSupport.sender;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbruptPersistenceTest {
    @TempDir
    Path tempDir;

    @Test
    void committedEvidenceAndTombstonesSurviveHaltedWriterProcess() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            Path path = tempDir.resolve("abrupt");
            UUID id = UUID.randomUUID();
            UUID inboxId = UUID.randomUUID();
            UUID processedId = UUID.randomUUID();
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String classPath = System.getProperty("surefire.test.class.path");
            if (classPath == null) {
                classPath = System.getProperty("java.class.path");
            }
            Process process = new ProcessBuilder(
                    javaExecutable,
                    "-cp",
                    classPath,
                    AbruptStoreWriter.class.getName(),
                    path.toString(),
                    id.toString(),
                    inboxId.toString(),
                    processedId.toString()
            ).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }

            List<DeliveryTransferPacket> replayed = new ArrayList<>();
            DeliveryLimits limits = new DeliveryLimits(256, 4, 1024, 2);
            try (H2DeliveryStore store = H2DeliveryStore.open(path, limits)) {
                DurableDeliverySession session = new DurableDeliverySession("peer", store);
                session.attachAuthenticated(new Object(),
                        sender(packet -> replayed.add((DeliveryTransferPacket) packet)));
                assertEquals(inboxId, session.pollInbox(2).getFirst().transferId());
                assertArrayEquals(new byte[]{21, 22}, session.pollInbox(2).getFirst().payload());
                assertEquals(2, session.inboxRecordCount());
                Object token = new Object();
                List<Object> receipts = new ArrayList<>();
                session.attachAuthenticated(token, receipts::add);
                receipts.clear();
                session.handleTransfer(token, new DeliveryTransferPacket(processedId, new byte[]{31, 32}));
                assertEquals(1, receipts.size());
                assertTrue(receipts.getFirst() instanceof DeliveryAckPacket);
                assertEquals(1, session.pollInbox(2).size());
                assertEquals(2, session.inboxRecordCount());
            }
            assertEquals(1, replayed.size());
            assertEquals(id, replayed.getFirst().transferId());
            assertArrayEquals(new byte[]{11, 12}, replayed.getFirst().payload());
        });
    }
}
