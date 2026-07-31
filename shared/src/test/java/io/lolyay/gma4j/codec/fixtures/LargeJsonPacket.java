package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.AutoCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

import java.util.List;
import java.util.Map;

public record LargeJsonPacket(List<String> items, Map<String, Long> index) implements GMAPacket<LargeJsonPacket> {

    public static final PacketType<LargeJsonPacket> TYPE = new PacketType<>(0, new AutoCodec<>(LargeJsonPacket.class));

    @Override
    public PacketType<LargeJsonPacket> getPacketType() {
        return TYPE;
    }
}
