package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record DeliveryTransferPacket(UUID transferId, byte[] payload)
        implements GMAPacket<DeliveryTransferPacket> {
    public static final PacketType<DeliveryTransferPacket> TYPE = new PacketType<>(0, new CustomCodec<>(
            DeliveryTransferPacket.class,
            DeliveryTransferPacket::encode,
            DeliveryTransferPacket::decode
    ));

    public DeliveryTransferPacket {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > DeliveryLimits.CONSERVATIVE_MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload exceeds packet limit");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public PacketType<DeliveryTransferPacket> getPacketType() {
        return TYPE;
    }

    private static byte[] encode(DeliveryTransferPacket packet) {
        ByteWriter writer = new ByteWriter();
        writer.writeUUID(packet.transferId);
        writer.writePrefixedBytes(packet.payload);
        return Arrays.copyOf(writer.getBuf(), writer.length());
    }

    private static DeliveryTransferPacket decode(byte[] data) {
        try {
            ByteReader reader = new ByteReader(data);
            UUID transferId = reader.readUUID();
            byte[] payload = reader.readPrefixedBytes(DeliveryLimits.CONSERVATIVE_MAX_PAYLOAD_BYTES);
            if (reader.isReadable()) {
                throw new IllegalArgumentException("trailing transfer packet bytes");
            }
            return new DeliveryTransferPacket(transferId, payload);
        } catch (IndexOutOfBoundsException exception) {
            throw new IllegalArgumentException("malformed transfer packet", exception);
        }
    }
}
