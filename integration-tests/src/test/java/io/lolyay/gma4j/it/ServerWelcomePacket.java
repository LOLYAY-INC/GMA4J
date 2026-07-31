package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

import java.nio.charset.StandardCharsets;

public record ServerWelcomePacket(String greeting, int connectedClients) implements GMAPacket<ServerWelcomePacket> {

    private static final int MAX_GREETING = 1024;

    public static final PacketType<ServerWelcomePacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            ServerWelcomePacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writePrefixedBytes(packet.greeting().getBytes(StandardCharsets.UTF_8));
                writer.writeInt(packet.connectedClients());
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                return new ServerWelcomePacket(
                        new String(reader.readPrefixedBytes(MAX_GREETING), StandardCharsets.UTF_8),
                        reader.readInt()
                );
            }
    ));

    @Override
    public PacketType<ServerWelcomePacket> getPacketType() {
        return TYPE;
    }
}
