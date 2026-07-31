package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.codec.fixtures.BinaryPacket;
import io.lolyay.gma4j.codec.fixtures.BlobPacket;
import io.lolyay.gma4j.codec.fixtures.ComplexPacket;
import io.lolyay.gma4j.codec.fixtures.LargeJsonPacket;
import io.lolyay.gma4j.codec.fixtures.TinyPacket;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.encryption.PacketCryptor;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PacketPipelineTest {

    private static PacketPipeline pipeline;

    private static IPacketDistributor noopDistributor() {
        return new IPacketDistributor() {
            @Override
            public <T extends GMAPacket<T>> void distribute(T packet) {
            }
        };
    }

    @BeforeAll
    static void registerAndWarmup() {
        CodecRegistry registry = CodecRegistry.getInstance();
        registry.addCodec(ComplexPacket.TYPE);
        registry.addCodec(LargeJsonPacket.TYPE);
        registry.addCodec(BinaryPacket.TYPE);
        registry.addCodec(BlobPacket.TYPE);
        registry.addCodec(TinyPacket.TYPE);
        registry.warmup();
        pipeline = new PacketPipeline(noopDistributor());
    }

    @Test
    void customRoundTrip() {
        BinaryPacket packet = new BinaryPacket(5, 6L, true, "seven", 8);
        BinaryPacket back = pipeline.decode(pipeline.encode(packet));
        assertEquals(packet, back);
    }

    @Test
    void autoRoundTrip() {
        ComplexPacket packet = new ComplexPacket("p", 1, 2L, 3.0, false,
                List.of("x"), Map.of("a", 1), List.of(), ComplexPacket.Suit.DIAMONDS, List.of(9), "u");
        ComplexPacket back = pipeline.decode(pipeline.encode(packet));
        assertEquals(packet, back);
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
        byte[] encoded = pipeline.encode(packet);
        assertEquals((byte) 1, encoded[0], "large packet should be compressed");
        LargeJsonPacket back = pipeline.decode(encoded);
        assertEquals(packet, back);
    }

    @Test
    void tinyPacketNotCompressed() {
        byte[] encoded = pipeline.encode(new TinyPacket(7));
        assertEquals((byte) 0, encoded[0], "tiny packet should not be compressed");
        TinyPacket back = pipeline.decode(encoded);
        assertEquals(new TinyPacket(7), back);
    }

    @Test
    void tooShortThrows() {
        assertThrows(PacketCodingException.class, () -> pipeline.decode(new byte[3]));
    }

    @Test
    void invalidPacketIdThrows() {
        assertThrows(PacketCodingException.class,
                () -> pipeline.decode(new byte[]{0, 0, 0x7F, (byte) 0xFF}));
    }

    @Test
    void invalidCodecTypeThrows() {
        assertThrows(PacketCodingException.class,
                () -> pipeline.decode(new byte[]{0, 99, 0, 0}));
    }

    @Test
    void encryptedRoundTrip() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        PacketPipeline client = new PacketPipeline(noopDistributor());
        client.setCryptor(new PacketCryptor(key, false));
        PacketPipeline server = new PacketPipeline(noopDistributor());
        server.setCryptor(new PacketCryptor(key, true));

        BinaryPacket packet = new BinaryPacket(11, 22L, false, "encrypted", 33);
        byte[] wire = client.encode(packet);
        BinaryPacket back = server.decode(wire);
        assertEquals(packet, back);
    }

    @Test
    void tamperedCiphertextRejected() {
        byte[] key = new byte[32];
        PacketPipeline client = new PacketPipeline(noopDistributor());
        client.setCryptor(new PacketCryptor(key, false));
        PacketPipeline server = new PacketPipeline(noopDistributor());
        server.setCryptor(new PacketCryptor(key, true));

        byte[] wire = client.encode(new BinaryPacket(1, 1, true, "x", 1));
        wire[wire.length - 1] ^= 0x01;
        assertThrows(IllegalStateException.class, () -> {
            BinaryPacket ignored = server.decode(wire);
        });
    }
}
