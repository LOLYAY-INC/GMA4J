package io.lolyay.gma4j.net.codec.systemcodec.c2s;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record C2SKeepAlivePacket(long id) implements GMAPacket<C2SKeepAlivePacket> {

    public static final CustomCodec<C2SKeepAlivePacket> CODEC = new CustomCodec<>(C2SKeepAlivePacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writeLong(packet.id);
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                return new C2SKeepAlivePacket(
                        reader.readLong()
                );
            }
    );

    @Override
    public PacketType<C2SKeepAlivePacket> getPacketType() {
        return SystemCodec.C_2_S_KEEPALIVE_PACKET;
    }
}
