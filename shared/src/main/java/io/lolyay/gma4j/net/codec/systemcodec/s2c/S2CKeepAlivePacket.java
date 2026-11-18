package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.PacketByteBuf;

public record S2CKeepAlivePacket(long id) implements GMAPacket<S2CKeepAlivePacket> {

    public static final CustomCodec<S2CKeepAlivePacket> CODEC = new CustomCodec<>(S2CKeepAlivePacket.class,
            packet -> {
                PacketByteBuf writer = PacketByteBuf.allocate();
                writer.writeLong(packet.id);
                return writer.toByteArray();
            },
            data -> {
                PacketByteBuf reader = PacketByteBuf.wrap(data);
                return new S2CKeepAlivePacket(
                        reader.readLong()
                );
            }
    );

    @Override
    public PacketType<S2CKeepAlivePacket> getPacketType() {
        return SystemCodec.S_2_C_KEEPALIVE_PACKET;
    }
}
