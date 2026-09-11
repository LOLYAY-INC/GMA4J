package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.codec.fixtures.GatedPacket;
import io.lolyay.gma4j.codec.fixtures.TestCodecs;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.connection.ConnectionSettings;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor;
import io.lolyay.gma4j.net.codec.systemcodec.c2s.C2SKeepAlivePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketPipelineAuthGateTest {

    @BeforeAll
    static void prepareRegistry() {
        TestCodecs.registerAll();
    }

    private record Harness(PacketPipeline pipeline, AtomicBoolean closed, List<GMAPacket<?>> received) {
        static Harness of(AtomicBoolean gate) {
            AtomicBoolean closed = new AtomicBoolean();
            List<GMAPacket<?>> received = new CopyOnWriteArrayList<>();
            IPacketDistributor distributor = new IPacketDistributor() {
                @Override
                public <T extends GMAPacket<T>> void distribute(T packet) {
                    received.add(packet);
                }
            };
            PacketPipeline pipeline = new PacketPipeline(() -> closed.set(true), distributor,
                    new ConnectionSettings(), gate::get);
            return new Harness(pipeline, closed, received);
        }
    }

    private static PacketPipeline sender() {
        return new PacketPipeline(() -> {}, new IPacketDistributor() {
            @Override
            public <T extends GMAPacket<T>> void distribute(T packet) {
            }
        });
    }

    @Test
    void applicationPacketBeforeAuthClosesWithoutDeserializing() {
        byte[] wire = sender().encode(new GatedPacket(42));
        Harness receiver = Harness.of(new AtomicBoolean(false));
        int before = GatedPacket.DESERIALIZATIONS.get();

        receiver.pipeline().decodeAndPassDown(wire);

        assertTrue(receiver.closed().get(), "unauthenticated app packet must close the connection");
        assertTrue(receiver.received().isEmpty());
        assertEquals(before, GatedPacket.DESERIALIZATIONS.get(), "payload must stay opaque before auth");
    }

    @Test
    void systemPacketPassesBeforeAuth() {
        byte[] wire = sender().encode(new C2SKeepAlivePacket(7L));
        Harness receiver = Harness.of(new AtomicBoolean(false));

        receiver.pipeline().decodeAndPassDown(wire);

        assertFalse(receiver.closed().get());
        assertEquals(1, receiver.received().size());
        assertInstanceOf(C2SKeepAlivePacket.class, receiver.received().get(0));
    }

    @Test
    void applicationPacketAfterAuthIsDistributed() {
        AtomicBoolean gate = new AtomicBoolean(false);
        Harness receiver = Harness.of(gate);
        PacketPipeline sender = sender();

        byte[] first = sender.encode(new C2SKeepAlivePacket(1L));
        receiver.pipeline().decodeAndPassDown(first);
        gate.set(true);

        byte[] second = sender.encode(new GatedPacket(1337));
        receiver.pipeline().decodeAndPassDown(second);

        assertFalse(receiver.closed().get());
        assertEquals(2, receiver.received().size());
        assertEquals(new GatedPacket(1337), receiver.received().get(1));
    }
}
