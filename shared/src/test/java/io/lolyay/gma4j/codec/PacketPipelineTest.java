package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.codec.fixtures.BinaryPacket;
import io.lolyay.gma4j.codec.fixtures.ComplexPacket;
import io.lolyay.gma4j.codec.fixtures.IncompressiblePacket;
import io.lolyay.gma4j.codec.fixtures.LargeJsonPacket;
import io.lolyay.gma4j.codec.fixtures.TestCodecs;
import io.lolyay.gma4j.codec.fixtures.TinyPacket;
import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.encryption.PacketCryptor;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketPipelineTest {

    @BeforeAll
    static void registerAndWarmup() {
        TestCodecs.registerAll();
    }

    @Test
    void closeRequestDoesNotWaitForActiveHandler() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cleanupCount = new AtomicInteger();
        PacketPipeline receiver = new PacketPipeline(() -> {}, new IPacketDistributor() {
            @Override
            public <T extends GMAPacket<T>> void distribute(T packet) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void close() {
                cleanupCount.incrementAndGet();
            }
        });
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> dispatch = executor.submit(() ->
                    receiver.decodeAndPassDown(pipeline().encode(new TinyPacket(7))));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        java.time.Duration.ofSeconds(1), receiver::requestClose);
                assertThrows(IllegalStateException.class, () -> receiver.encode(new TinyPacket(8)));
                assertEquals(0, cleanupCount.get());
            } finally {
                release.countDown();
            }
            dispatch.get(5, TimeUnit.SECONDS);
            receiver.close();
            assertEquals(1, cleanupCount.get());
        }
    }

    @Test
    void customRoundTrip() {
        PacketPipeline pipeline = pipeline();
        BinaryPacket packet = new BinaryPacket(5, 6L, true, "seven", 8);
        assertEquals(packet, pipeline.decode(pipeline.encode(packet)));
    }

    @Test
    void autoRoundTrip() {
        PacketPipeline pipeline = pipeline();
        ComplexPacket packet = new ComplexPacket("p", 1, 2L, 3.0, false,
                List.of("x"), Map.of("a", 1), List.of(), ComplexPacket.Suit.DIAMONDS, List.of(9), "u");
        assertEquals(packet, pipeline.decode(pipeline.encode(packet)));
    }

    @Test
    void headerAndSequenceAreStable() {
        PacketPipeline pipeline = pipeline();
        byte[] first = pipeline.encode(new TinyPacket(7));
        byte[] second = pipeline.encode(new TinyPacket(8));

        assertEquals(0, ByteReader.readInt(first, 0));
        assertEquals(1, ByteReader.readInt(second, 0));
        assertEquals(0, first[4]);
        assertEquals(TinyPacket.TYPE.codec().getCodecType().ordinal(), first[5] & 0xFF);
        assertEquals(TinyPacket.TYPE.numericId(), ((first[6] & 0xFF) << 8) | (first[7] & 0xFF));
    }

    @Test
    void largePacketIsCompressed() {
        List<String> items = new ArrayList<>();
        Map<String, Long> index = new HashMap<>();
        for (int i = 0; i < 5_000; i++) {
            items.add("padding-padding-padding-" + i);
            index.put("k" + i, (long) i);
        }
        LargeJsonPacket packet = new LargeJsonPacket(items, index);
        PacketPipeline pipeline = pipeline();
        byte[] encoded = pipeline.encode(packet);

        assertEquals(1, encoded[4], "large packet should be compressed");
        assertEquals(packet, pipeline.decode(encoded));
    }

    @Test
    void disabledCompressionLeavesPacketUncompressed() {
        boolean enabled = SharedConfig.PACKET_COMPRESSION_ENABLED;
        int threshold = SharedConfig.PACKET_COMPRESSION_THRESHOLD;
        try {
            SharedConfig.PACKET_COMPRESSION_ENABLED = false;
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = 1;
            byte[] encoded = pipeline().encode(new TinyPacket(7));
            assertEquals(0, encoded[4]);
        } finally {
            SharedConfig.PACKET_COMPRESSION_ENABLED = enabled;
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = threshold;
        }
    }

    @Test
    void compressionPredicateIsPerPacket() {
        int threshold = SharedConfig.PACKET_COMPRESSION_THRESHOLD;
        try {
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = 1;
            String blob = "data".repeat(2_000);

            byte[] plain = pipeline().encode(new IncompressiblePacket(blob, false));
            assertEquals(0, plain[4], "predicate must veto compression");
            assertEquals(new IncompressiblePacket(blob, false), pipeline().decode(plain));

            byte[] squeezed = pipeline().encode(new IncompressiblePacket(blob, true));
            assertEquals(1, squeezed[4]);
            assertEquals(new IncompressiblePacket(blob, true), pipeline().decode(squeezed));
        } finally {
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = threshold;
        }
    }

    @Test
    void rejectsEveryTruncatedHeader() {
        PacketPipeline pipeline = pipeline();
        for (int length = 0; length < 8; length++) {
            byte[] data = new byte[length];
            assertThrows(PacketCodingException.class, () -> pipeline.decode(data));
        }
    }

    @Test
    void invalidPacketIdThrows() {
        assertThrows(PacketCodingException.class, () -> pipeline().decode(header(0, 0, 0x7FFF)));
    }

    @Test
    void invalidCodecTypeThrows() {
        assertThrows(PacketCodingException.class, () -> pipeline().decode(header(0, 99, 0)));
    }

    @Test
    void mismatchDoesNotAdvanceExpectedSequence() {
        PacketPipeline sender = pipeline();
        PacketPipeline receiver = pipeline();
        byte[] first = sender.encode(new TinyPacket(1));
        byte[] second = sender.encode(new TinyPacket(2));

        assertEquals(new TinyPacket(1), receiver.decode(first));
        byte[] wrong = second.clone();
        ByteWriter.writeInt(wrong, 2, 0);
        assertNull(receiver.decode(wrong));
        assertEquals(new TinyPacket(2), receiver.decode(second));
    }

    @Test
    void closesAfterConfiguredSequenceMismatches() {
        int max = SharedConfig.MAX_OUT_OF_ORDER;
        AtomicInteger closes = new AtomicInteger();
        PacketPipeline receiver = new PacketPipeline(closes::incrementAndGet, noopDistributor());

        for (int i = 0; i < max; i++) {
            assertNull(receiver.decode(header(1, 0, 0)));
        }
        assertEquals(1, closes.get());
    }

    @Test
    void compressedRejectionConsumesSequenceWithoutDistribution() {
        boolean reject = SharedConfig.REJECT_COMPRESSED_PACKETS;
        boolean compressionEnabled = SharedConfig.PACKET_COMPRESSION_ENABLED;
        int threshold = SharedConfig.PACKET_COMPRESSION_THRESHOLD;
        AtomicInteger distributed = new AtomicInteger();
        IPacketDistributor distributor = new IPacketDistributor() {
            @Override
            public <T extends GMAPacket<T>> void distribute(T packet) {
                distributed.incrementAndGet();
            }
        };
        PacketPipeline sender = pipeline();
        PacketPipeline receiver = new PacketPipeline(() -> {}, distributor);
        try {
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = 1;
            LargeJsonPacket large = new LargeJsonPacket(
                    List.of("padding".repeat(2_000)), Map.of("value", 1L));
            byte[] compressed = sender.encode(large);
            assertEquals(1, compressed[4]);
            SharedConfig.REJECT_COMPRESSED_PACKETS = true;
            receiver.decodeAndPassDown(compressed);
            assertEquals(0, distributed.get());

            SharedConfig.REJECT_COMPRESSED_PACKETS = false;
            SharedConfig.PACKET_COMPRESSION_ENABLED = false;
            receiver.decodeAndPassDown(sender.encode(new TinyPacket(2)));
            assertEquals(1, distributed.get());
        } finally {
            SharedConfig.REJECT_COMPRESSED_PACKETS = reject;
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = threshold;
            SharedConfig.PACKET_COMPRESSION_ENABLED = compressionEnabled;
        }
    }

    @Test
    void malformedPacketsCloseAtConfiguredThreshold() {
        int max = SharedConfig.MAX_DECODE_ERRORS;
        AtomicInteger closes = new AtomicInteger();
        PacketPipeline receiver = new PacketPipeline(closes::incrementAndGet, noopDistributor());

        for (int sequence = 0; sequence < max; sequence++) {
            receiver.decodeAndPassDown(header(sequence, 0, 0x7FFF));
        }
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> receiver.decode(header(max, 0, 0)));
    }

    @Test
    void encryptedRoundTrip() {
        byte[] key = key();
        PacketPipeline client = new PacketPipeline(() -> {}, noopDistributor());
        client.setCryptor(new PacketCryptor(key, false));
        PacketPipeline server = new PacketPipeline(() -> {}, noopDistributor());
        server.setCryptor(new PacketCryptor(key, true));

        BinaryPacket packet = new BinaryPacket(11, 22L, false, "encrypted", 33);
        assertEquals(packet, server.decode(client.encode(packet)));
    }

    @Test
    void rejectsShortDecryptedFrame() {
        byte[] key = key();
        PacketCryptor clientCryptor = new PacketCryptor(key, false);
        PacketPipeline server = new PacketPipeline(() -> {}, noopDistributor());
        server.setCryptor(new PacketCryptor(key, true));

        assertThrows(PacketCodingException.class, () -> server.decode(clientCryptor.encrypt(new byte[7])));
    }

    @Test
    void tamperedCiphertextRejected() {
        byte[] key = key();
        PacketPipeline client = new PacketPipeline(() -> {}, noopDistributor());
        client.setCryptor(new PacketCryptor(key, false));
        PacketPipeline server = new PacketPipeline(() -> {}, noopDistributor());
        server.setCryptor(new PacketCryptor(key, true));

        byte[] wire = client.encode(new BinaryPacket(1, 1, true, "x", 1));
        wire[wire.length - 1] ^= 0x01;
        assertThrows(PacketCodingException.class, () -> server.decode(wire));
    }

    @Test
    void closeIsIdempotentBeforeEncryption() {
        AtomicInteger closes = new AtomicInteger();
        IPacketDistributor distributor = new IPacketDistributor() {
            @Override
            public <T extends GMAPacket<T>> void distribute(T packet) {
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        PacketPipeline pipeline = new PacketPipeline(() -> {}, distributor);

        assertDoesNotThrow(pipeline::close);
        assertDoesNotThrow(pipeline::close);
        assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, () -> pipeline.encode(new TinyPacket(1)));
        assertThrows(IllegalStateException.class, () -> pipeline.decode(new byte[8]));
        assertDoesNotThrow(() -> pipeline.decodeAndPassDown(new byte[8]));
    }

    @Test
    void protocolCloseHookFailureStillClosesPipeline() {
        PacketPipeline pipeline = new PacketPipeline(() -> {
            throw new IllegalStateException("close failed");
        }, noopDistributor());
        for (int i = 1; i < SharedConfig.MAX_OUT_OF_ORDER; i++) {
            assertNull(pipeline.decode(header(1, 0, 0)));
        }
        assertThrows(IllegalStateException.class, () -> pipeline.decode(header(1, 0, 0)));
        assertThrows(IllegalStateException.class, () -> pipeline.encode(new TinyPacket(1)));
    }

    @Test
    void oversizedSerializedPayloadIsRejectedBeforeCompression() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        try {
            SharedConfig.MAX_PACKET_SIZE = 1_000;
            LargeJsonPacket large = new LargeJsonPacket(
                    List.of("padding".repeat(2_000)), Map.of("value", 1L));
            assertThrows(PacketCodingException.class, () -> pipeline().encode(large));
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
        }
    }

    @Test
    void finalEncryptedFrameHonorsMaximum() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        try {
            SharedConfig.MAX_PACKET_SIZE = 32;
            PacketPipeline pipeline = new PacketPipeline(() -> {}, noopDistributor());
            pipeline.setCryptor(new PacketCryptor(key(), false));
            assertThrows(PacketCodingException.class, () -> pipeline.encode(new TinyPacket(1)));
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
        }
    }

    @Test
    void sessionPacketLimitClosesSender() {
        int max = SharedConfig.MAX_SESSION_PACKETS;
        AtomicInteger closes = new AtomicInteger();
        try {
            SharedConfig.MAX_SESSION_PACKETS = 2;
            PacketPipeline sender = new PacketPipeline(closes::incrementAndGet, noopDistributor());
            sender.encode(new TinyPacket(1));
            sender.encode(new TinyPacket(2));
            assertThrows(IllegalStateException.class, () -> sender.encode(new TinyPacket(3)));
            assertEquals(1, closes.get());
            assertThrows(IllegalStateException.class, () -> sender.encode(new TinyPacket(4)));
            assertEquals(1, closes.get());
        } finally {
            SharedConfig.MAX_SESSION_PACKETS = max;
        }
    }

    @Test
    void sessionPacketLimitClosesReceiver() {
        int max = SharedConfig.MAX_SESSION_PACKETS;
        AtomicInteger closes = new AtomicInteger();
        try {
            PacketPipeline sender = pipeline();
            byte[] first = sender.encode(new TinyPacket(1));
            byte[] second = sender.encode(new TinyPacket(2));
            byte[] third = sender.encode(new TinyPacket(3));

            SharedConfig.MAX_SESSION_PACKETS = 2;
            PacketPipeline receiver = new PacketPipeline(closes::incrementAndGet, noopDistributor());
            assertEquals(new TinyPacket(1), receiver.decode(first));
            assertEquals(new TinyPacket(2), receiver.decode(second));
            assertNull(receiver.decode(third));
            assertEquals(1, closes.get());
            assertThrows(IllegalStateException.class, () -> receiver.decode(third));
        } finally {
            SharedConfig.MAX_SESSION_PACKETS = max;
        }
    }

    @Test
    void expiredKeyClosesBothDirections() throws Exception {
        long maxAge = SharedConfig.MAX_SESSION_AGE_MS;
        AtomicInteger closes = new AtomicInteger();
        try {
            SharedConfig.MAX_SESSION_AGE_MS = 1;
            byte[] key = key();
            PacketPipeline client = pipeline();
            client.setCryptor(new PacketCryptor(key, false));
            byte[] wire = client.encode(new TinyPacket(1));

            PacketPipeline server = new PacketPipeline(closes::incrementAndGet, noopDistributor());
            server.setCryptor(new PacketCryptor(key, true));
            Thread.sleep(5);
            assertNull(server.decode(wire));
            assertEquals(1, closes.get());

            assertThrows(IllegalStateException.class, () -> client.encode(new TinyPacket(2)));
        } finally {
            SharedConfig.MAX_SESSION_AGE_MS = maxAge;
        }
    }

    @Test
    void unencryptedPipelineHasNoKeyAge() throws Exception {
        long maxAge = SharedConfig.MAX_SESSION_AGE_MS;
        try {
            SharedConfig.MAX_SESSION_AGE_MS = 1;
            PacketPipeline pipeline = pipeline();
            Thread.sleep(5);
            assertDoesNotThrow(() -> pipeline.encode(new TinyPacket(1)));
        } finally {
            SharedConfig.MAX_SESSION_AGE_MS = maxAge;
        }
    }

    private static PacketPipeline pipeline() {
        return new PacketPipeline(() -> {}, noopDistributor());
    }

    private static IPacketDistributor noopDistributor() {
        return new IPacketDistributor() {
            @Override
            public <T extends GMAPacket<T>> void distribute(T packet) {
            }
        };
    }

    private static byte[] header(int sequence, int codec, int packetId) {
        byte[] data = new byte[8];
        ByteWriter.writeInt(data, sequence, 0);
        data[5] = (byte) codec;
        data[6] = (byte) (packetId >> 8);
        data[7] = (byte) packetId;
        return data;
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }
}
