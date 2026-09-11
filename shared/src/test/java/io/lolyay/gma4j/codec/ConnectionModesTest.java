package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.codec.fixtures.BinaryPacket;
import io.lolyay.gma4j.codec.fixtures.ComplexPacket;
import io.lolyay.gma4j.codec.fixtures.IncompressiblePacket;
import io.lolyay.gma4j.codec.fixtures.LargeJsonPacket;
import io.lolyay.gma4j.codec.fixtures.TinyPacket;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.CompressionUtil;
import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.codec.PacketFlags;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.connection.ConnectionSettings;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SModeRequestPacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CModeStatusPacket;
import io.lolyay.gma4j.net.shared.CodecType;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionModesTest {

    @BeforeAll
    static void registerAndWarmup() {
        CodecRegistry registry = CodecRegistry.getInstance();
        registry.addCodec(ComplexPacket.TYPE);
        registry.addCodec(LargeJsonPacket.TYPE);
        registry.addCodec(BinaryPacket.TYPE);
        registry.addCodec(TinyPacket.TYPE);
        registry.addCodec(IncompressiblePacket.TYPE);
        registry.warmup();
    }

    @Test
    void reservedBitsRejected() {
        PacketPipeline sender = pipeline(new ConnectionSettings());
        PacketPipeline receiver = pipeline(new ConnectionSettings());
        byte[] frame = sender.encode(new TinyPacket(1));
        frame[4] |= 0x10;
        assertThrows(PacketCodingException.class, () -> receiver.decode(frame));
    }

    @Test
    void urgentFlagRequiresLowLatencyGrant() {
        ConnectionSettings senderSettings = new ConnectionSettings();
        senderSettings.apply(true, false, 0);
        PacketPipeline sender = pipeline(senderSettings);

        byte[] frame = sender.encode(new TinyPacket(2), true);
        assertTrue((frame[4] & PacketFlags.URGENT) != 0);

        PacketPipeline ungranted = pipeline(new ConnectionSettings());
        assertThrows(PacketCodingException.class, () -> ungranted.decode(frame));

        ConnectionSettings grantedSettings = new ConnectionSettings();
        grantedSettings.apply(true, false, 0);
        PacketPipeline granted = pipeline(grantedSettings);
        assertEquals(new TinyPacket(2), granted.decode(frame));
    }

    @Test
    void urgentIgnoredWithoutLowLatencyMode() {
        PacketPipeline sender = pipeline(new ConnectionSettings());
        byte[] frame = sender.encode(new TinyPacket(3), true);
        assertEquals(0, frame[4] & PacketFlags.URGENT);
    }

    @Test
    void bigPacketRoundTripWithGrant() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        boolean compression = SharedConfig.PACKET_COMPRESSION_ENABLED;
        try {
            SharedConfig.MAX_PACKET_SIZE = 256;
            SharedConfig.PACKET_COMPRESSION_ENABLED = false;
            ConnectionSettings senderSettings = new ConnectionSettings();
            ConnectionSettings receiverSettings = new ConnectionSettings();
            senderSettings.apply(false, true, 10_000);
            receiverSettings.apply(false, true, 10_000);

            BinaryPacket packet = new BinaryPacket(1, 2L, true, "x".repeat(1_000), 3);
            byte[] frame = pipeline(senderSettings).encode(packet);
            assertTrue((frame[4] & PacketFlags.BIG) != 0);
            assertEquals(packet, pipeline(receiverSettings).decode(frame));
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
            SharedConfig.PACKET_COMPRESSION_ENABLED = compression;
        }
    }

    @Test
    void oversizedFrameWithoutBigFlagRejected() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        boolean compression = SharedConfig.PACKET_COMPRESSION_ENABLED;
        try {
            SharedConfig.MAX_PACKET_SIZE = 256;
            SharedConfig.PACKET_COMPRESSION_ENABLED = false;
            ConnectionSettings senderSettings = new ConnectionSettings();
            ConnectionSettings receiverSettings = new ConnectionSettings();
            senderSettings.apply(false, true, 10_000);
            receiverSettings.apply(false, true, 10_000);

            byte[] frame = pipeline(senderSettings).encode(new BinaryPacket(1, 2L, true, "x".repeat(1_000), 3));
            frame[4] &= (byte) ~PacketFlags.BIG;
            assertThrows(PacketCodingException.class, () -> pipeline(receiverSettings).decode(frame));
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
            SharedConfig.PACKET_COMPRESSION_ENABLED = compression;
        }
    }

    @Test
    void bigFlagOnSmallPacketRejected() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        try {
            SharedConfig.MAX_PACKET_SIZE = 256;
            ConnectionSettings receiverSettings = new ConnectionSettings();
            receiverSettings.apply(false, true, 10_000);

            byte[] frame = pipeline(new ConnectionSettings()).encode(new TinyPacket(4));
            frame[4] |= PacketFlags.BIG;
            assertThrows(PacketCodingException.class, () -> pipeline(receiverSettings).decode(frame));
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
        }
    }

    @Test
    void bigPacketRejectedWithoutGrant() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        boolean compression = SharedConfig.PACKET_COMPRESSION_ENABLED;
        try {
            SharedConfig.MAX_PACKET_SIZE = 256;
            SharedConfig.PACKET_COMPRESSION_ENABLED = false;
            ConnectionSettings senderSettings = new ConnectionSettings();
            senderSettings.apply(false, true, 10_000);

            byte[] frame = pipeline(senderSettings).encode(new BinaryPacket(1, 2L, true, "x".repeat(1_000), 3));
            assertThrows(PacketCodingException.class, () -> pipeline(new ConnectionSettings()).decode(frame));
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
            SharedConfig.PACKET_COMPRESSION_ENABLED = compression;
        }
    }

    @Test
    void lowLatencySkipsCompression() {
        int threshold = SharedConfig.PACKET_COMPRESSION_THRESHOLD;
        try {
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = 1;
            LargeJsonPacket packet = new LargeJsonPacket(
                    List.of("padding".repeat(2_000)), Map.of("value", 1L));

            byte[] normal = pipeline(new ConnectionSettings()).encode(packet);
            assertTrue((normal[4] & PacketFlags.COMPRESSED) != 0);

            ConnectionSettings lowLatency = new ConnectionSettings();
            lowLatency.apply(true, false, 0);
            byte[] fast = pipeline(lowLatency).encode(packet);
            assertEquals(0, fast[4] & PacketFlags.COMPRESSED);
        } finally {
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = threshold;
        }
    }

    @Test
    void downgradeKeepsReceiveAllowances() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        try {
            SharedConfig.MAX_PACKET_SIZE = 256;
            ConnectionSettings settings = new ConnectionSettings();
            settings.apply(true, true, 10_000);
            assertEquals(10_000, settings.sendLimit());

            settings.apply(false, false, 0);
            assertEquals(256, settings.sendLimit());
            assertEquals(10_000, settings.receiveLimit());
            assertTrue(settings.isBigReceiveAllowed());
            assertTrue(settings.isUrgentReceiveAllowed());
            assertFalse(settings.isLowLatency());
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
        }
    }

    @Test
    void bigFlagFollowsPlaintextSizeThroughCompression() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        int threshold = SharedConfig.PACKET_COMPRESSION_THRESHOLD;
        try {
            SharedConfig.MAX_PACKET_SIZE = 256;
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = 1;
            ConnectionSettings senderSettings = new ConnectionSettings();
            ConnectionSettings receiverSettings = new ConnectionSettings();
            senderSettings.apply(false, true, 10_000);
            receiverSettings.apply(false, true, 10_000);

            BinaryPacket packet = new BinaryPacket(1, 2L, true, "x".repeat(1_000), 3);
            byte[] frame = pipeline(senderSettings).encode(packet);
            assertTrue((frame[4] & PacketFlags.COMPRESSED) != 0);
            assertTrue(frame.length <= 256, "compressible payload should shrink below base");
            assertTrue((frame[4] & PacketFlags.BIG) != 0, "big must reflect the plaintext size");
            assertEquals(packet, pipeline(receiverSettings).decode(frame));

            byte[] ungrantedCopy = pipeline(senderSettings).encode(packet);
            assertThrows(PacketCodingException.class, () -> pipeline(new ConnectionSettings()).decode(ungrantedCopy));
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = threshold;
        }
    }

    @Test
    void unflaggedCompressedBombRejected() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        int threshold = SharedConfig.PACKET_COMPRESSION_THRESHOLD;
        try {
            SharedConfig.MAX_PACKET_SIZE = 256;
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = 1;
            ConnectionSettings senderSettings = new ConnectionSettings();
            ConnectionSettings receiverSettings = new ConnectionSettings();
            senderSettings.apply(false, true, 10_000);
            receiverSettings.apply(false, true, 10_000);

            byte[] frame = pipeline(senderSettings).encode(new BinaryPacket(1, 2L, true, "x".repeat(1_000), 3));
            frame[4] &= (byte) ~PacketFlags.BIG;
            assertThrows(PacketCodingException.class, () -> pipeline(receiverSettings).decode(frame));
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
            SharedConfig.PACKET_COMPRESSION_THRESHOLD = threshold;
        }
    }

    @Test
    void receiveAllowanceLeadsTheGrant() {
        int max = SharedConfig.MAX_PACKET_SIZE;
        try {
            SharedConfig.MAX_PACKET_SIZE = 256;
            ConnectionSettings settings = new ConnectionSettings();
            settings.raiseReceiveAllowance(10_000);
            assertEquals(10_000, settings.receiveAllowance());
            assertEquals(256, settings.receiveLimit(), "policy limit must not move before the grant");
            assertFalse(settings.isBigReceiveAllowed());

            settings.apply(false, true, 20_000);
            assertEquals(20_000, settings.receiveLimit());
            assertEquals(20_000, settings.receiveAllowance());
        } finally {
            SharedConfig.MAX_PACKET_SIZE = max;
        }
    }

    @Test
    void decompressionIsBounded() throws DataFormatException {
        byte[] data = new byte[5_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 7);
        }
        byte[] compressed = CompressionUtil.compress(data);
        assertThrows(DataFormatException.class, () -> CompressionUtil.decompress(compressed, 1_000));
        assertArrayEquals(data, CompressionUtil.decompress(compressed, 5_000));
    }

    @Test
    void modePacketCodecsRoundTrip() {
        C2SModeRequestPacket request = new C2SModeRequestPacket(true, true, 123_456);
        assertEquals(request, C2SModeRequestPacket.CODEC.deserialize(
                C2SModeRequestPacket.CODEC.serialize(request), CodecType.BINARY_CUSTOM));

        S2CModeStatusPacket status = new S2CModeStatusPacket(false, true, 654_321);
        assertEquals(status, S2CModeStatusPacket.CODEC.deserialize(
                S2CModeStatusPacket.CODEC.serialize(status), CodecType.BINARY_CUSTOM));

        byte[] tampered = C2SModeRequestPacket.CODEC.serialize(request);
        tampered[0] = 0x7;
        assertThrows(IllegalArgumentException.class,
                () -> C2SModeRequestPacket.CODEC.deserialize(tampered, CodecType.BINARY_CUSTOM));
    }

    private static PacketPipeline pipeline(ConnectionSettings settings) {
        return new PacketPipeline(() -> {}, noopDistributor(), settings);
    }

    private static IPacketDistributor noopDistributor() {
        return new IPacketDistributor() {
            @Override
            public <T extends GMAPacket<T>> void distribute(T packet) {
            }
        };
    }
}
