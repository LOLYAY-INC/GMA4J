package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;

public record S2CKeepAlivePacket(long id) implements GMAPacket<S2CKeepAlivePacket> {

    public static final CustomCodec<S2CKeepAlivePacket> CODEC = new CustomCodec<>(S2CKeepAlivePacket.class,
            (writer, packet) -> {
                writer.writeLong(packet.id);
            },
            reader -> {
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
