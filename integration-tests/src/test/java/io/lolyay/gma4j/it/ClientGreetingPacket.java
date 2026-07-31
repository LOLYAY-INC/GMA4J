package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.packet.AutoCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

public record ClientGreetingPacket(String clientName, int favoriteNumber) implements GMAPacket<ClientGreetingPacket> {

    public static final PacketType<ClientGreetingPacket> TYPE =
            new PacketType<>(0, new AutoCodec<>(ClientGreetingPacket.class));

    @Override
    public PacketType<ClientGreetingPacket> getPacketType() {
        return TYPE;
    }
}
