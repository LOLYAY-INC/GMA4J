package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

public record ComplexPacketS2c(ComplexBody body) implements GMAPacket<ComplexPacketS2c> {

    public static final PacketType<ComplexPacketS2c> TYPE = new PacketType<>(0, new CustomCodec<>(
            ComplexPacketS2c.class,
            (writer, packet) -> {
                ComplexBody.write(writer, packet.body());
            },
            reader -> new ComplexPacketS2c(ComplexBody.read(reader))
    ));

    @Override
    public PacketType<ComplexPacketS2c> getPacketType() {
        return TYPE;
    }
}
