package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record S2CKeepAlivePacket(long id) implements GMAPacket<S2CKeepAlivePacket> {

    public static final CustomCodec<S2CKeepAlivePacket> CODEC = new CustomCodec<>(S2CKeepAlivePacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writeLong(packet.id);
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
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
