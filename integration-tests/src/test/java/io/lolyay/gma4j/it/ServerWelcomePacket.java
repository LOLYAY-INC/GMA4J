package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;

import java.nio.charset.StandardCharsets;

public record ServerWelcomePacket(String greeting, int connectedClients) implements GMAPacket<ServerWelcomePacket> {

    private static final int MAX_GREETING = 1024;

    public static final PacketType<ServerWelcomePacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            ServerWelcomePacket.class,
            (writer, packet) -> {
                writer.writePrefixedBytes(packet.greeting().getBytes(StandardCharsets.UTF_8));
                writer.writeInt(packet.connectedClients());
            },
            reader -> {
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
