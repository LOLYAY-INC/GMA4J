package io.lolyay.gma4j.net.codec.systemcodec.c2s;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.shared.SharedConfig;

public record C2SKeepAlivePacket(long id) implements GMAPacket<C2SKeepAlivePacket> {

    public static final CustomCodec<C2SKeepAlivePacket> CODEC = new CustomCodec<>(C2SKeepAlivePacket.class,
            (writer, packet) -> {
                writer.writeLong(packet.id);
            },
            reader -> {
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
