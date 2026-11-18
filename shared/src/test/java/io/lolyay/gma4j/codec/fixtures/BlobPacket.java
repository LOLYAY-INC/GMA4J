package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

public record BlobPacket(byte[] blob, long checksum) implements GMAPacket<BlobPacket> {

    private static final int MAX_BLOB = 32 << 20;

    public static final PacketType<BlobPacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            BlobPacket.class,
            (writer, packet) -> {
                writer.writePrefixedBytes(packet.blob());
                writer.writeLong(packet.checksum());
            },
            reader -> {
                return new BlobPacket(
                        reader.readPrefixedBytes(MAX_BLOB),
                        reader.readLong()
                );
            }
    ));

    @Override
    public PacketType<BlobPacket> getPacketType() {
        return TYPE;
    }
}
