package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.AutoCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

public record TinyPacket(int n) implements GMAPacket<TinyPacket> {

    public static final PacketType<TinyPacket> TYPE = new PacketType<>(0, new AutoCodec<>(TinyPacket.class));

    @Override
    public PacketType<TinyPacket> getPacketType() {
        return TYPE;
    }
}
