package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.AutoCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

import java.util.List;
import java.util.Map;

public record ComplexPacket(
        String name,
        int count,
        long huge,
        double ratio,
        boolean flag,
        List<String> tags,
        Map<String, Integer> scores,
        List<Nested> nested,
        Suit suit,
        List<Integer> numbers,
        String unicode
) implements GMAPacket<ComplexPacket> {

    public static final PacketType<ComplexPacket> TYPE = new PacketType<>(0, new AutoCodec<>(ComplexPacket.class));

    @Override
    public PacketType<ComplexPacket> getPacketType() {
        return TYPE;
    }

    public record Nested(String key, List<Double> values, Map<String, Nested> children) {
    }

    public enum Suit {
        HEARTS, DIAMONDS, CLUBS, SPADES
    }
}
