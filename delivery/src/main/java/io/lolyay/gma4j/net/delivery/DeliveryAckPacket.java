package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record DeliveryAckPacket(UUID transferId, byte[] digest)
        implements GMAPacket<DeliveryAckPacket> {
    public static final PacketType<DeliveryAckPacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            DeliveryAckPacket.class,
            DeliveryAckPacket::encode,
            DeliveryAckPacket::decode
    ));

    public DeliveryAckPacket {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(digest, "digest");
        if (digest.length != DeliveryDigest.LENGTH) {
            throw new IllegalArgumentException("digest must contain 32 bytes");
        }
        digest = digest.clone();
    }

    @Override
    public byte[] digest() {
        return digest.clone();
    }

    @Override
    public PacketType<DeliveryAckPacket> getPacketType() {
        return TYPE;
    }

    private static byte[] encode(DeliveryAckPacket packet) {
        ByteWriter writer = new ByteWriter();
        writer.writeUUID(packet.transferId);
        writer.writeBytes(packet.digest);
        return Arrays.copyOf(writer.getBuf(), writer.length());
    }

    private static DeliveryAckPacket decode(byte[] data) {
        if (data.length != 16 + DeliveryDigest.LENGTH) {
            throw new IllegalArgumentException("malformed ACK packet");
        }
        ByteReader reader = new ByteReader(data);
        return new DeliveryAckPacket(reader.readUUID(), reader.readBytes(DeliveryDigest.LENGTH));
    }
}
