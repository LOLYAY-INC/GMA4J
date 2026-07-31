package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.encryption.EncryptionMode;
import io.lolyay.gma4j.net.codec.encryption.utils.DHUtil;
import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record S2CHelloPacket(
        // Encryption
        EncryptionMode selectedEncryptionMode,
        byte[] dhParams,
        byte[] cert,
        byte[] serverNonce,

        // Auth
        GmaAuthType selectedAuthType,

        byte[] signature

) implements GMAPacket<S2CHelloPacket> {
    private static final int MAX_CERT_SIZE = 4 * 1024;
    private static final int MAX_SIGNATURE_SIZE = 80;
    private static final int NONCE_SIZE = 32;

    @Override
    public PacketType<S2CHelloPacket> getPacketType() {
        return SystemCodec.S_2_C_HELLO_PACKET;
    }
    public static final CustomCodec<S2CHelloPacket> CODEC = new CustomCodec<>(S2CHelloPacket.class,

            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writeByte(packet.selectedEncryptionMode().ordinal());
                writer.writeBytes(packet.dhParams());
                writer.writePrefixedBytes(packet.cert());
                writer.writeBytes(packet.serverNonce());
                writer.writeByte(packet.selectedAuthType().ordinal());
                writer.writePrefixedBytes(packet.signature());
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                return new S2CHelloPacket(
                        EncryptionMode.values()[reader.readByte() & 0xFF],
                        reader.readBytes(DHUtil.PUBLIC_KEY_LENGTH),
                        reader.readPrefixedBytes(MAX_CERT_SIZE),
                        reader.readBytes(NONCE_SIZE),
                        GmaAuthType.values()[reader.readByte() & 0xFF],
                        reader.readPrefixedBytes(MAX_SIGNATURE_SIZE)

                );
            }
    );
}
