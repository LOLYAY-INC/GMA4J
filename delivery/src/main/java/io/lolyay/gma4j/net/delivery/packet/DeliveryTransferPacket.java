package io.lolyay.gma4j.net.delivery.packet;

import io.lolyay.gma4j.net.codec.packet.CustomCodec;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.delivery.data.DeliveryLimits;
import io.lolyay.gma4j.net.util.PacketByteBuf;

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

    private static void encode(PacketByteBuf writer, DeliveryTransferPacket packet) {
        writer.writeUUID(packet.transferId);
        writer.writePrefixedBytes(packet.payload);
    }

    private static DeliveryTransferPacket decode(PacketByteBuf reader) {
        try {
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
