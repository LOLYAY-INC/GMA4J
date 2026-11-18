package io.lolyay.gma4j.net.codec.systemcodec.c2s;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.shared.SharedConfig;

import java.nio.charset.StandardCharsets;

public record C2SAuthPacket(GmaAuthType authType, byte[] extraAuthData, String claimedClientId) implements GMAPacket<C2SAuthPacket> {

    private static final int MAX_CLIENT_ID_SIZE = 256;

    public static final CustomCodec<C2SAuthPacket> CODEC = new CustomCodec<>(C2SAuthPacket.class,
            (writer, packet) -> {
                writer.writeByte(packet.authType().ordinal());
                writer.writePrefixedBytes(packet.extraAuthData());
                writer.writePrefixedBytes(packet.claimedClientId().getBytes(StandardCharsets.UTF_8));
            },
            reader -> {
                return new C2SAuthPacket(
                        GmaAuthType.values()[reader.readUnsignedByte()],
                        reader.readPrefixedBytes(SharedConfig.EXTRA_AUTH_DATA_MAX_SIZE),
                        new String(reader.readPrefixedBytes(MAX_CLIENT_ID_SIZE), StandardCharsets.UTF_8)
                );
            }
    );

    @Override
    public PacketType<C2SAuthPacket> getPacketType() {
        return SystemCodec.C_2_S_AUTH_PACKET;
    }
}
