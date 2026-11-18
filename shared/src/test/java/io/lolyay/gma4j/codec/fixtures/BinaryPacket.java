package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.util.PacketByteBuf;

import java.nio.charset.StandardCharsets;

public record BinaryPacket(int i, long l, boolean b, String s, int varint) implements GMAPacket<BinaryPacket> {

    private static final int MAX_STRING = 1 << 20;

    public static final PacketType<BinaryPacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            BinaryPacket.class,
            packet -> {
                PacketByteBuf writer = PacketByteBuf.allocate();
                writer.writeInt(packet.i());
                writer.writeLong(packet.l());
                writer.writeBoolean(packet.b());
                writer.writePrefixedBytes(packet.s().getBytes(StandardCharsets.UTF_8));
                writer.writeVarInt(packet.varint());
                return writer.toByteArray();
            },
            data -> {
                PacketByteBuf reader = PacketByteBuf.wrap(data);
                return new BinaryPacket(
                        reader.readInt(),
                        reader.readLong(),
                        reader.readBoolean(),
                        new String(reader.readPrefixedBytes(MAX_STRING), StandardCharsets.UTF_8),
                        reader.readVarInt()
                );
            }
    ));

    @Override
    public PacketType<BinaryPacket> getPacketType() {
        return TYPE;
    }
}
