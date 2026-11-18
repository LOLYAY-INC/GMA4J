package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

public record ComplexPacketC2s(ComplexBody body) implements GMAPacket<ComplexPacketC2s> {

    public static final PacketType<ComplexPacketC2s> TYPE = new PacketType<>(0, new CustomCodec<>(
            ComplexPacketC2s.class,
            (writer, packet) -> {
                ComplexBody.write(writer, packet.body());
            },
            reader -> new ComplexPacketC2s(ComplexBody.read(reader))
    ));

    @Override
    public PacketType<ComplexPacketC2s> getPacketType() {
        return TYPE;
    }
}
