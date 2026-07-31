package io.lolyay.gma4j.net.codec.systemcodec.c2s;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record C2SAuthResponsePacket(byte[] response) implements GMAPacket<C2SAuthResponsePacket> {

    public static final CustomCodec<C2SAuthResponsePacket> CODEC = new CustomCodec<>(C2SAuthResponsePacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writePrefixedBytes(packet.response());
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                return new C2SAuthResponsePacket(
                        reader.readPrefixedBytes(SharedConfig.EXTRA_AUTH_DATA_MAX_SIZE)
                );
            }
    );

    @Override
    public PacketType<C2SAuthResponsePacket> getPacketType() {
        return SystemCodec.C_2_S_AUTH_RESPONSE;
    }
}
