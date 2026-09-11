package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

import java.util.concurrent.atomic.AtomicInteger;

public record GatedPacket(int value) implements GMAPacket<GatedPacket> {

    public static final AtomicInteger DESERIALIZATIONS = new AtomicInteger();

    public static final PacketType<GatedPacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            GatedPacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writeInt(packet.value());
                return writer.getBuf();
            },
            data -> {
                DESERIALIZATIONS.incrementAndGet();
                return new GatedPacket(new ByteReader(data).readInt());
            }
    ));

    @Override
    public PacketType<GatedPacket> getPacketType() {
        return TYPE;
    }
}
