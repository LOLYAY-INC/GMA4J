package io.lolyay.gma4j.net.codec.systemcodec.s2c;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.systemcodec.SystemCodec;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

import java.util.UUID;

public record S2CAuthChallengePacket(
        byte[] challenge,
        UUID clientId
) implements GMAPacket<S2CAuthChallengePacket> {
    private static final int MAX_CHALLENGE_SIZE = 4 * 1024;

    @Override
    public PacketType<S2CAuthChallengePacket> getPacketType() {
        return SystemCodec.S_2_C_AUTH_CHALLENGE_PACKET;
    }
    public static final CustomCodec<S2CAuthChallengePacket> CODEC = new CustomCodec<>(S2CAuthChallengePacket.class,
            packet -> {
                ByteWriter writer = new ByteWriter();
                writer.writePrefixedBytes(packet.challenge());
                writer.writeUUID(packet.clientId());
                return writer.getBuf();
            },
            data -> {
                ByteReader reader = new ByteReader(data);
                return new S2CAuthChallengePacket(
                        reader.readPrefixedBytes(MAX_CHALLENGE_SIZE),
                        reader.readUUID()
                );
            }
    );
}
