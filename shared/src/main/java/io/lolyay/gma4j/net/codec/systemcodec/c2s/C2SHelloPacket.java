package io.lolyay.gma4j.net.codec.systemcodec.c2s;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.encryption.EncryptionMode;
import io.lolyay.gma4j.net.codec.encryption.utils.DHUtil;
import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record C2SHelloPacket(
    short gma4jVersion,
    String connectUri,

    // Codec
    short sysCodecVersion,
    int encVersion,
    byte[] codecHash,
    int sysPacketEnd,
    int packetCount,

    // Encryption Data
    List<EncryptionMode> supportedEncryptionModes,
    byte[] clientNonce,
    byte[] dhParams,
    byte[] knownServerCertHash,


    // Auth Data
    List<GmaAuthType> supportedAuthTypes,
    byte[] customAuthData


) implements GMAPacket<C2SHelloPacket> {
    @Override
    public PacketType<C2SHelloPacket> getPacketType() {
        return SystemCodec.C_2_S_HELLO_PACKET;
    }

    private final static int MAX_CUSTOM_AUTH_DATA_SIZE = 2 * 1024;

    public static final CustomCodec<C2SHelloPacket> CODEC = new CustomCodec<>(C2SHelloPacket.class,

            packet -> {
                ByteWriter writer = new ByteWriter();

                writer.writeShort(packet.gma4jVersion());

                writer.writePrefixedBytes(packet.connectUri().getBytes(StandardCharsets.UTF_8));

                writer.writeByte(packet.sysCodecVersion());
                writer.writeByte(packet.encVersion());
                writer.writeBytes(packet.codecHash());
                writer.writeShort(packet.sysPacketEnd());
                writer.writeByte(packet.packetCount());

                writer.writePrefixedEnumArray(packet.supportedEncryptionModes());
                writer.writeBytes(packet.clientNonce());
                writer.writeBytes(packet.dhParams());
                writer.writeBytes((packet.knownServerCertHash() == null || packet.knownServerCertHash.length != 32)? new byte[32] : packet.knownServerCertHash());

                writer.writePrefixedEnumArray(packet.supportedAuthTypes());
                writer.writePrefixedBytes(packet.customAuthData());
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                return new C2SHelloPacket(
                        reader.readShort(),
                        new String(reader.readPrefixedBytes(MAX_CUSTOM_AUTH_DATA_SIZE), StandardCharsets.UTF_8),
                        (short) (reader.readByte() & 0xFF),
                        reader.readByte() & 0xFF,
                        reader.readBytes(16),
                        reader.readShort() & 0xFFFF,
                        reader.readByte() & 0xFF,

                        reader.readPrefixedEnumArray(EncryptionMode.class, EncryptionMode.values().length),
                        reader.readBytes(32),
                        reader.readBytes(DHUtil.PUBLIC_KEY_LENGTH),
                        reader.readBytes(32),
                        reader.readPrefixedEnumArray(GmaAuthType.class, GmaAuthType.values().length),
                        reader.readPrefixedBytes(MAX_CUSTOM_AUTH_DATA_SIZE)
                );
            }
    );
}
