package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

public record BlobPacket(byte[] blob, long checksum) implements GMAPacket<BlobPacket> {

    private static final int MAX_BLOB = 32 << 20;

    public static final PacketType<BlobPacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            BlobPacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writePrefixedBytes(packet.blob());
                writer.writeLong(packet.checksum());
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
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
