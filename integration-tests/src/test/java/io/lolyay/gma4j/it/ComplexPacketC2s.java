package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record ComplexPacketC2s(ComplexBody body) implements GMAPacket<ComplexPacketC2s> {

    public static final PacketType<ComplexPacketC2s> TYPE = new PacketType<>(0, new CustomCodec<>(
            ComplexPacketC2s.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                ComplexBody.write(writer, packet.body());
                return writer.getBuf();
            },
            data -> new ComplexPacketC2s(ComplexBody.read(new ByteReader(data)))
    ));

    @Override
    public PacketType<ComplexPacketC2s> getPacketType() {
        return TYPE;
    }
}
