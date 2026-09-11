package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record S2CModeStatusPacket(
        boolean lowLatency,
        boolean bigSize,
        int maxPacketSize
) implements GMAPacket<S2CModeStatusPacket> {

    @Override
    public PacketType<S2CModeStatusPacket> getPacketType() {
        return SystemCodec.S_2_C_MODE_STATUS_PACKET;
    }

    public static final CustomCodec<S2CModeStatusPacket> CODEC = new CustomCodec<>(S2CModeStatusPacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writeByte((packet.lowLatency() ? 0x1 : 0) | (packet.bigSize() ? 0x2 : 0));
                writer.writeVarInt(packet.maxPacketSize());
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                int flags = reader.readByte() & 0xFF;
                if ((flags & ~0x3) != 0) {
                    throw new IllegalArgumentException("Reserved mode flag bits set: " + flags);
                }
                return new S2CModeStatusPacket(
                        (flags & 0x1) != 0,
                        (flags & 0x2) != 0,
                        reader.readVarInt()
                );
            }
    );
}
