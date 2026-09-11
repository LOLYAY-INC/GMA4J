package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.AutoCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

public record IncompressiblePacket(String blob, boolean allowCompression) implements GMAPacket<IncompressiblePacket> {

    public static final PacketType<IncompressiblePacket> TYPE = new PacketType<>(0,
            new AutoCodec<>(IncompressiblePacket.class), IncompressiblePacket::allowCompression);

    @Override
    public PacketType<IncompressiblePacket> getPacketType() {
        return TYPE;
    }
}
