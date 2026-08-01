package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record ComplexPacketS2c(ComplexBody body) implements GMAPacket<ComplexPacketS2c> {

    public static final PacketType<ComplexPacketS2c> TYPE = new PacketType<>(0, new CustomCodec<>(
            ComplexPacketS2c.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                ComplexBody.write(writer, packet.body());
                return writer.getBuf();
            },
            data -> new ComplexPacketS2c(ComplexBody.read(new ByteReader(data)))
    ));

    @Override
    public PacketType<ComplexPacketS2c> getPacketType() {
        return TYPE;
    }
}
