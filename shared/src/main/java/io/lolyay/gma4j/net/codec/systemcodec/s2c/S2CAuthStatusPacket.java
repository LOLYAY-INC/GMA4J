package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.PacketByteBuf;

public record S2CAuthStatusPacket(
        boolean success
) implements GMAPacket<S2CAuthStatusPacket> {

    @Override
    public PacketType<S2CAuthStatusPacket> getPacketType() {
        return SystemCodec.S_2_C_AUTH_STATUS_PACKET;
    }
    public static final CustomCodec<S2CAuthStatusPacket> CODEC = new CustomCodec<>(S2CAuthStatusPacket.class,
            packet -> {
                PacketByteBuf writer = PacketByteBuf.allocate();
                writer.writeBoolean(packet.success());
                return writer.toByteArray();
            },
            data -> {
                PacketByteBuf reader = PacketByteBuf.wrap(data);
                return new S2CAuthStatusPacket(
                        reader.readBoolean()
                );
            }
    );
}
