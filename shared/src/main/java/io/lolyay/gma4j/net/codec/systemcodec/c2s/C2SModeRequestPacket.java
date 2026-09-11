package io.lolyay.gma4j.net.codec.systemcodec.c2s;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record C2SModeRequestPacket(
        boolean lowLatency,
        boolean bigSize,
        int requestedMaxPacketSize
) implements GMAPacket<C2SModeRequestPacket> {

    @Override
    public PacketType<C2SModeRequestPacket> getPacketType() {
        return SystemCodec.C_2_S_MODE_REQUEST_PACKET;
    }

    public static final CustomCodec<C2SModeRequestPacket> CODEC = new CustomCodec<>(C2SModeRequestPacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writeByte((packet.lowLatency() ? 0x1 : 0) | (packet.bigSize() ? 0x2 : 0));
                writer.writeVarInt(packet.requestedMaxPacketSize());
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                int flags = reader.readByte() & 0xFF;
                if ((flags & ~0x3) != 0) {
                    throw new IllegalArgumentException("Reserved mode flag bits set: " + flags);
                }
                return new C2SModeRequestPacket(
                        (flags & 0x1) != 0,
                        (flags & 0x2) != 0,
                        reader.readVarInt()
                );
            }
    );
}
