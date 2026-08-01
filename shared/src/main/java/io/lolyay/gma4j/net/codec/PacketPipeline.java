package io.lolyay.gma4j.net.codec;

import io.lolyay.gma4j.net.codec.encryption.PacketCryptor;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor;
import io.lolyay.gma4j.net.shared.CodecType;
import io.lolyay.gma4j.net.shared.SharedConfig;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.zip.DataFormatException;

@Slf4j
@RequiredArgsConstructor
public class PacketPipeline {
    @Setter
    private PacketCryptor cryptor;
    private boolean isClosed = false;
    private final IPacketDistributor distributor;

    public <T extends GMAPacket<T>> byte[] encode(T packet) {
        byte[] encoded = encodePacket(packet);
        if(cryptor != null) {
            return cryptor.encrypt(encoded);
        }
        return encoded;
    }

    public <T extends GMAPacket<T>> T decode(byte[] data) {
        if(cryptor != null) {
            data = cryptor.decrypt(data);
        }
        return decodePacket(data);
    }

    public <T extends GMAPacket<T>> void decodeAndPassDown(byte[] data) {
        if(cryptor != null) {
            data = cryptor.decrypt(data);
        }
        passDown(data);
    }

    public <T extends GMAPacket<T>> void passDown(byte[] data) {
        T packet;
        try {
            packet = decodePacket(data);
        } catch (PacketCodingException e) {
            log.error("Error while decoding packet", e);
            return;
        }
        try {
            distributor.distribute(packet);
        } catch (Exception e) {
            log.error("Error while handling packet", e);
        }
    }

    private <T extends GMAPacket<T>> T decodePacket(byte[] data) throws PacketCodingException {
        if(data.length < 4) throw new PacketCodingException("Packet too short");

        boolean compressed = (data[0] & 0x1) != 0; // 0
        //TODO: we currently waste 7 bits here; other compression algos?
        int codecOrdinal = data[1] & 0xFF; // 1
        int packetId = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF); // 2, 3


        if(!CodecRegistry.getInstance().isValid(packetId)) throw new PacketCodingException("Invalid packet id: " + packetId);
        if(codecOrdinal >= CodecType.values().length) throw new PacketCodingException("Invalid codec type: " + data[1]);

        CodecType codecType = CodecType.values()[codecOrdinal];

        PacketType<T> packetType = CodecRegistry.getInstance().getCodec(packetId);

        if(packetType.codec().getCodecType() != codecType && SharedConfig.FORCE_CODEC) throw new PacketCodingException("Codec Not supported for packet: " + codecType);

        byte[] packet = Arrays.copyOfRange(data, 4, data.length);

        if(compressed) {
            int compressedLength = packet.length;
            try {
                packet = CompressionUtil.decompress(packet);
            } catch (DataFormatException e) {
                throw new PacketCodingException("Error while decompressing packet", e);
            }
            log.debug("Decompressed incoming packet {}: {} -> {} bytes", packetId, compressedLength, packet.length);
        }

        try {
            return packetType.codec().deserialize(packet, codecType);
        } catch (Exception e) {
            throw new PacketCodingException("Error decoding Packet: " + packetId, e);
        }
    }

    private <T extends GMAPacket<T>> byte[] encodePacket(T packet) throws PacketCodingException {
        byte[] payload;
        boolean compressed = false;
        CodecType codecType = packet.getPacketType().codec().getCodecType();
        int packetId = packet.getPacketType().numericId();

        try {
            payload = packet.getPacketType().codec().serialize(packet);
        } catch (Exception e) {
            throw new PacketCodingException("Error encoding packet: " + packet.getPacketType().numericId(), e);
        }

        if(payload.length >= SharedConfig.PACKET_COMPRESSION_THRESHOLD) {
            int uncompressedLength = payload.length;
            try {
                payload = CompressionUtil.compress(payload);
            } catch (Exception e) {
                throw new PacketCodingException("Error compressing packet: " + packetId, e);
            }
            compressed = true;
            log.debug("Compressed outgoing packet {}: {} -> {} bytes", packetId, uncompressedLength, payload.length);
        }

        byte[] encodedPacket = new byte[payload.length + 4];
        encodedPacket[0] = (byte) (compressed ? 1 : 0);
        encodedPacket[1] = (byte) codecType.ordinal();
        encodedPacket[2] = (byte) (packetId >> 8);
        encodedPacket[3] = (byte) packetId;
        System.arraycopy(payload, 0, encodedPacket, 4, payload.length);

        return encodedPacket;
    }

    public void close() {
        isClosed = true;
    }
}
