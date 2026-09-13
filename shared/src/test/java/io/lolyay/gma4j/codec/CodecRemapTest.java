package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.codec.fixtures.BinaryPacket;
import io.lolyay.gma4j.codec.fixtures.TestCodecs;
import io.lolyay.gma4j.codec.fixtures.TinyPacket;
import io.lolyay.gma4j.net.codec.CodecConfig;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.CodecRemap;
import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.packet.AutoCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CCodecStateUpdatePacket;
import io.lolyay.gma4j.net.codec.systemcodec.s2c.S2CCodecStateUpdatePacket.CodecUpdateState;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Models two nodes whose registries assigned ids differently by giving each pipeline a remap
 * built against the other's table. One JVM, one registry, so the "server" table is synthesized.
 */
class CodecRemapTest {

    @BeforeAll
    static void registerAndWarmup() {
        TestCodecs.registerAll();
    }

    /** A synthetic server whose id for TinyPacket differs from ours (it is BinaryPacket's local id) */
    private static CodecRemap remapWhereServerSwapsTinyAndBinary() {
        CodecConfig config = CodecRegistry.getInstance().getConfig();
        int tinyLocal = TinyPacket.TYPE.numericId();
        int binaryLocal = BinaryPacket.TYPE.numericId();
        List<CodecUpdateState> serverTable = config.codecState().stream()
                .map(state -> {
                    PacketType<?> type = state.packetType();
                    int serverId = type == TinyPacket.TYPE ? binaryLocal
                            : type == BinaryPacket.TYPE ? tinyLocal
                            : type.numericId();
                    return new CodecUpdateState(state.packetHash(),
                            type.codec().getClazz().getSimpleName(), serverId, type.getUserSetId(), "");
                })
                .toList();
        return CodecRemap.from(new S2CCodecStateUpdatePacket(0, config.globalCodecState(), serverTable),
                CodecRegistry.getInstance());
    }

    @Test
    void encodeUsesServerIdAndDecodeMapsItBack() {
        CodecRemap remap = remapWhereServerSwapsTinyAndBinary();
        PacketPipeline client = pipeline();
        client.setRemap(remap);

        byte[] wire = client.encode(new TinyPacket(42));
        int wireId = ((wire[6] & 0xFF) << 8) | (wire[7] & 0xFF);
        assertEquals(BinaryPacket.TYPE.numericId(), wireId, "wire must carry the server's id for TinyPacket");

        // a peer holding the same table maps the server id back to TinyPacket, not BinaryPacket
        PacketPipeline peer = pipeline();
        peer.setRemap(remap);
        assertEquals(new TinyPacket(42), peer.decode(wire));
    }

    @Test
    void systemPacketsPassThroughUnremapped() {
        CodecRemap remap = remapWhereServerSwapsTinyAndBinary();
        int bound = CodecRegistry.getInstance().systemIdBound();
        for (int id = 0; id < bound; id++) {
            assertEquals(id, remap.toRemote(id));
        }
    }

    @Test
    void packetUnknownToServerCannotBeSent() {
        CodecConfig config = CodecRegistry.getInstance().getConfig();
        // server table that simply lacks TinyPacket
        List<CodecUpdateState> serverTable = config.codecState().stream()
                .filter(state -> state.packetType() != TinyPacket.TYPE)
                .map(state -> new CodecUpdateState(state.packetHash(),
                        state.packetType().codec().getClazz().getSimpleName(),
                        state.packetType().numericId(), state.packetType().getUserSetId(), ""))
                .toList();
        CodecRemap remap = CodecRemap.from(
                new S2CCodecStateUpdatePacket(0, config.globalCodecState(), serverTable),
                CodecRegistry.getInstance());
        assertEquals(-1, remap.toRemote(TinyPacket.TYPE.numericId()));

        PacketPipeline client = pipeline();
        client.setRemap(remap);
        PacketCodingException failure = assertThrows(PacketCodingException.class,
                () -> client.encode(new TinyPacket(1)));
        assertTrue(failure.getMessage().contains("not known to the peer"));
    }

    @Test
    void serverIdWeDoNotKnowIsDroppedNotFatal() {
        CodecRemap remap = remapWhereServerSwapsTinyAndBinary();
        PacketPipeline peer = pipeline();
        peer.setRemap(remap);
        // an id far past anything in the table: dropped, sequence still consumed, no close
        byte[] data = new byte[8];
        data[6] = 0x7F;
        data[7] = (byte) 0xFF;
        assertNull(peer.decode(data));
    }

    @Test
    void namespaceAndUserSetIdMatchBeatsHashAndName() {
        // two packets, distinct classes, same namespace+id would be a bug; different namespace, same id is legal
        PacketType<NsA> a = new PacketType<>(1, new AutoCodec<>(NsA.class), "myplugin");
        PacketType<NsB> b = new PacketType<>(1, new AutoCodec<>(NsB.class), "otherplugin");
        assertEquals("myplugin", a.getNamespace());
        assertEquals(1, a.getUserSetId());
        assertEquals(1, b.getUserSetId());
        assertThrows(IllegalArgumentException.class,
                () -> new PacketType<>(1, new AutoCodec<>(NsA.class), "  "));
    }

    @Test
    void codecStateUpdateRoundTripsNamespace() {
        CodecUpdateState namespaced = new CodecUpdateState(new byte[16], "Foo", 9, 1, "myplugin");
        CodecUpdateState plain = new CodecUpdateState(new byte[16], "Bar", 10, 2, "");
        S2CCodecStateUpdatePacket packet = new S2CCodecStateUpdatePacket(7, new byte[16], List.of(namespaced, plain));
        byte[] bytes = S2CCodecStateUpdatePacket.CODEC.serialize(packet);
        S2CCodecStateUpdatePacket back = S2CCodecStateUpdatePacket.CODEC.deserialize(bytes,
                io.lolyay.gma4j.net.shared.CodecType.BINARY_CUSTOM);
        assertEquals("myplugin", back.states().get(0).namespace());
        assertEquals("", back.states().get(1).namespace());
        assertEquals(9, back.states().get(0).packetId());
    }

    @Test
    void namespacedEntryNeverFallsThroughToHashOrName() {
        // server has ("otherplugin", 1); we only have TinyPacket (unnamespaced) with the same fingerprint
        CodecConfig config = CodecRegistry.getInstance().getConfig();
        CodecConfig.CodecState tiny = config.codecState().stream()
                .filter(s -> s.packetType() == TinyPacket.TYPE).findFirst().orElseThrow();
        CodecUpdateState foreign = new CodecUpdateState(tiny.packetHash(), "TinyPacket",
                tiny.packetType().numericId(), tiny.packetType().getUserSetId(), "otherplugin");
        CodecRemap remap = CodecRemap.from(
                new S2CCodecStateUpdatePacket(0, config.globalCodecState(), List.of(foreign)),
                CodecRegistry.getInstance());
        // same hash, same name, but namespaced: must NOT map onto our unnamespaced TinyPacket
        assertNull(remap.toLocal(tiny.packetType().numericId(), CodecRegistry.getInstance()));
        assertEquals(-1, remap.toRemote(TinyPacket.TYPE.numericId()));
    }

    @Test
    void ambiguousLocalMatchFailsInstallation() {
        // two local entries that both claim the server's name-only key
        CodecConfig config = CodecRegistry.getInstance().getConfig();
        CodecConfig.CodecState tiny = config.codecState().stream()
                .filter(s -> s.packetType() == TinyPacket.TYPE).findFirst().orElseThrow();
        List<CodecConfig.CodecState> duplicated = List.of(tiny, tiny);
        CodecUpdateState entry = new CodecUpdateState(new byte[16], "TinyPacket", 50, 0, "");
        assertThrows(IllegalStateException.class,
                () -> CodecRemap.build(List.of(entry), duplicated, CodecRegistry.getInstance().systemIdBound()));
    }

    @Test
    void namespaceOnlyDifferenceChangesGlobalHash() {
        PacketType<NsA> plain = new PacketType<>(1, new AutoCodec<>(NsA.class));
        PacketType<NsA> namespaced = new PacketType<>(1, new AutoCodec<>(NsA.class), "myplugin");
        byte[] a = CodecConfig.generate(List.of(plain)).globalCodecState();
        byte[] b = CodecConfig.generate(List.of(namespaced)).globalCodecState();
        assertFalse(java.util.Arrays.equals(a, b), "namespace must be part of the codec identity hash");
        // and the same identity produces the same hash
        assertArrayEquals(b, CodecConfig.generate(List.of(new PacketType<>(1, new AutoCodec<>(NsA.class), "myplugin"))).globalCodecState());
    }

    @Test
    void unknownIdsAreCappedForLenientPeers() {
        int previous = SharedConfig.MAX_UNKNOWN_PACKET_IDS;
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        try {
            SharedConfig.MAX_UNKNOWN_PACKET_IDS = 2;
            PacketPipeline peer = new PacketPipeline(closes::incrementAndGet, new IPacketDistributor() {
                @Override
                public <T extends GMAPacket<T>> void distribute(T packet) {
                }
            });
            peer.setRemap(remapWhereServerSwapsTinyAndBinary());
            assertNull(peer.decode(unknownIdFrame(0)));
            assertNull(peer.decode(unknownIdFrame(1)));
            assertEquals(0, closes.get());
            assertNull(peer.decode(unknownIdFrame(2)));
            assertEquals(1, closes.get(), "third unknown id must close the connection");
        } finally {
            SharedConfig.MAX_UNKNOWN_PACKET_IDS = previous;
        }
    }

    @Test
    void unauthenticatedPeerGetsNoLeniency() {
        boolean previous = SharedConfig.IGNORE_CODEC_HASH;
        try {
            SharedConfig.IGNORE_CODEC_HASH = true;
            PacketPipeline peer = new PacketPipeline(() -> {}, new IPacketDistributor() {
                @Override
                public <T extends GMAPacket<T>> void distribute(T packet) {
                }
            }, new io.lolyay.gma4j.net.codec.connection.ConnectionSettings(), () -> false);
            assertThrows(PacketCodingException.class, () -> peer.decode(unknownIdFrame(0)));
        } finally {
            SharedConfig.IGNORE_CODEC_HASH = previous;
        }
    }

    private static byte[] unknownIdFrame(int sequence) {
        byte[] data = new byte[8];
        data[3] = (byte) sequence;
        data[6] = 0x7F;
        data[7] = (byte) 0xFF;
        return data;
    }

    private static PacketPipeline pipeline() {
        return new PacketPipeline(() -> {}, new IPacketDistributor() {
            @Override
            public <T extends GMAPacket<T>> void distribute(T packet) {
            }
        });
    }

    record NsA(int v) implements GMAPacket<NsA> {
        @Override
        public PacketType<NsA> getPacketType() {
            throw new UnsupportedOperationException();
        }
    }

    record NsB(int v) implements GMAPacket<NsB> {
        @Override
        public PacketType<NsB> getPacketType() {
            throw new UnsupportedOperationException();
        }
    }
}
