package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.AutoCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

public record NestedJsonPacket(Object payload) implements GMAPacket<NestedJsonPacket> {

    public static final PacketType<NestedJsonPacket> TYPE = new PacketType<>(0, new AutoCodec<>(NestedJsonPacket.class));

    @Override
    public PacketType<NestedJsonPacket> getPacketType() {
        return TYPE;
    }
}
