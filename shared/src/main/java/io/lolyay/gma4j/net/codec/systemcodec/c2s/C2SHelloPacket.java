package io.lolyay.gma4j.net.codec.systemcodec.c2s;

import io.lolyay.gma4j.net.codec.ClientType;
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.encryption.EncryptionMode;
import io.lolyay.gma4j.net.codec.encryption.utils.DHUtil;
import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.PacketByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record C2SHelloPacket(
    short gma4jVersion,
    String connectUri,
    ClientType clientType,

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
                PacketByteBuf writer = PacketByteBuf.allocate();

                writer.writeShort(packet.gma4jVersion());

                writer.writePrefixedBytes(packet.connectUri().getBytes(StandardCharsets.UTF_8));

                writer.writeByte(packet.clientType().ordinal());

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
                return writer.toByteArray();
            },
            data -> {
                PacketByteBuf reader = PacketByteBuf.wrap(data);
                return new C2SHelloPacket(
                        reader.readShort(),
                        new String(reader.readPrefixedBytes(MAX_CUSTOM_AUTH_DATA_SIZE), StandardCharsets.UTF_8),
                        ClientType.values()[reader.readUnsignedByte()],
                        reader.readUnsignedByte(),
                        reader.readUnsignedByte(),
                        reader.readByteArray(16),
                        reader.readUnsignedShort(),
                        reader.readUnsignedByte(),

                        reader.readPrefixedEnumArray(EncryptionMode.class, EncryptionMode.values().length),
                        reader.readByteArray(32),
                        reader.readByteArray(DHUtil.PUBLIC_KEY_LENGTH),
                        reader.readByteArray(32),
                        reader.readPrefixedEnumArray(GmaAuthType.class, GmaAuthType.values().length),
                        reader.readPrefixedBytes(MAX_CUSTOM_AUTH_DATA_SIZE)
                );
            }
    );
}
