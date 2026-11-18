package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

import java.util.concurrent.atomic.AtomicInteger;

public record GatedPacket(int value) implements GMAPacket<GatedPacket> {

    public static final AtomicInteger DESERIALIZATIONS = new AtomicInteger();

    public static final PacketType<GatedPacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            GatedPacket.class,
            (writer, packet) -> {
                writer.writeInt(packet.value());
            },
            reader -> {
                DESERIALIZATIONS.incrementAndGet();
                return new GatedPacket(reader.readInt());
            }
    ));

    @Override
    public PacketType<GatedPacket> getPacketType() {
        return TYPE;
    }
}
