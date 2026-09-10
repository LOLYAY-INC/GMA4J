package io.lolyay.gma4j.net.codec;

import io.lolyay.gma4j.net.codec.encryption.PacketCryptor;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor;
import io.lolyay.gma4j.net.shared.CodecType;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;

@Slf4j
@RequiredArgsConstructor
public class PacketPipeline {
    @Setter
    private volatile PacketCryptor cryptor;
    private final Runnable closeHook;
    private final Object encodeLock = new Object();
    private final Object decodeLock = new Object();
    private final Object dispatchLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int maxDecodeErrors = Math.max(1, SharedConfig.MAX_DECODE_ERRORS);
    private int sequence;
    private int remoteSequence;
    private int outOfSequenceCount;
    private int decodeErrorCount;
    private final IPacketDistributor distributor;

    public <T extends GMAPacket<T>> byte[] encode(T packet) {
        synchronized (encodeLock) {
            requireOpen();
            byte[] encoded = encodePacket(packet, sequence);
            if (cryptor != null) {
                encoded = cryptor.encrypt(encoded);
            }
            if (encoded.length > SharedConfig.MAX_PACKET_SIZE) {
                throw new PacketCodingException("Packet too large: " + encoded.length + " > " + SharedConfig.MAX_PACKET_SIZE);
            }
            sequence++;
            return encoded;
        }
    }

    public <T extends GMAPacket<T>> void decodeAndPassDown(byte[] data) {
        synchronized (dispatchLock) {
            T packet;
            try {
                packet = decode(data);
            } catch (IllegalStateException e) {
                if (closed.get()) {
                    return;
                }
                throw e;
            } catch (PacketCodingException e) {
                decodeErrorCount++;
                if (decodeErrorCount >= maxDecodeErrors) {
                    log.warn("Closing connection after {} malformed packets: {}", decodeErrorCount, e.getMessage());
                    closeForProtocolViolation();
                } else {
                    log.debug("Dropping malformed packet ({}/{}): {}", decodeErrorCount,
                            maxDecodeErrors, e.getMessage());
                }
                return;
            }

            if (packet == null || closed.get()) {
                return;
            }
            decodeErrorCount = 0;

            try {
                distributor.distribute(packet);
            } catch (Exception e) {
                log.error("Error while handling packet", e);
            }
        }
    }

    public <T extends GMAPacket<T>> T decode(byte[] data) throws PacketCodingException {
        try {
            synchronized (decodeLock) {
                return decodeLocked(data);
            }
        } catch (ProtocolCloseSignal ignored) {
            closeForProtocolViolation();
            return null;
        }
    }

    private <T extends GMAPacket<T>> T decodeLocked(byte[] data) {
        requireOpen();
        validateWireFrame(data);

        if (cryptor != null) {
            try {
                data = cryptor.decrypt(data);
            } catch (RuntimeException e) {
                throw new PacketCodingException("Error while decrypting packet", e);
            }
        }
        if (data.length < 8) {
            throw new PacketCodingException("Packet too short after decryption");
        }

        int receivedSequence = ByteReader.readInt(data, 0);
        if (receivedSequence != remoteSequence) {
            log.error("Packet out of order: expected {}, got {}", remoteSequence, receivedSequence);
            if (++outOfSequenceCount >= SharedConfig.MAX_OUT_OF_ORDER) {
                throw new ProtocolCloseSignal();
            }
            return null;
        }
        remoteSequence++;
        outOfSequenceCount = 0;

        boolean compressed = (data[4] & 0x1) != 0;
        int codecOrdinal = data[5] & 0xFF;
        int packetId = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);

        if (!CodecRegistry.getInstance().isValid(packetId)) {
            throw new PacketCodingException("Invalid packet id: " + packetId);
        }
        if (codecOrdinal >= CodecType.values().length) {
            throw new PacketCodingException("Invalid codec type: " + codecOrdinal);
        }

        CodecType codecType = CodecType.values()[codecOrdinal];
        PacketType<T> packetType = CodecRegistry.getInstance().getCodec(packetId);
        if (packetType.codec().getCodecType() != codecType && SharedConfig.FORCE_CODEC) {
            throw new PacketCodingException("Codec not supported for packet: " + codecType);
        }

        byte[] payload = Arrays.copyOfRange(data, 8, data.length);
        if (compressed) {
            if (SharedConfig.REJECT_COMPRESSED_PACKETS) {
                log.warn("Rejecting compressed packet: {}", packetId);
                return null;
            }
            int compressedLength = payload.length;
            try {
                payload = CompressionUtil.decompress(payload);
            } catch (DataFormatException e) {
                throw new PacketCodingException("Error while decompressing packet", e);
            }
            log.debug("Decompressed incoming packet {}: {} -> {} bytes", packetId, compressedLength, payload.length);
        }

        try {
            return packetType.codec().deserialize(payload, codecType);
        } catch (Exception e) {
            throw new PacketCodingException("Error decoding packet: " + packetId, e);
        }
    }

    private <T extends GMAPacket<T>> byte[] encodePacket(T packet, int currentSequence) throws PacketCodingException {
        byte[] payload;
        boolean compressed = false;
        CodecType codecType = packet.getPacketType().codec().getCodecType();
        int packetId = packet.getPacketType().numericId();

        try {
            payload = packet.getPacketType().codec().serialize(packet);
        } catch (Exception e) {
            throw new PacketCodingException("Error encoding packet: " + packetId, e);
        }
        if (payload.length > SharedConfig.MAX_PACKET_SIZE) {
            throw new PacketCodingException("Packet payload too large: " + payload.length
                    + " > " + SharedConfig.MAX_PACKET_SIZE);
        }

        if (payload.length >= SharedConfig.PACKET_COMPRESSION_THRESHOLD && SharedConfig.PACKET_COMPRESSION_ENABLED) {
            int uncompressedLength = payload.length;
            try {
                byte[] compressedPayload = CompressionUtil.compress(payload);
                if (compressedPayload.length < payload.length) {
                    payload = compressedPayload;
                    compressed = true;
                    log.debug("Compressed outgoing packet {}: {} -> {} bytes", packetId, uncompressedLength, payload.length);
                }
            } catch (Exception e) {
                throw new PacketCodingException("Error compressing packet: " + packetId, e);
            }
        }

        byte[] encodedPacket = new byte[payload.length + 8];
        ByteWriter.writeInt(encodedPacket, currentSequence, 0);
        encodedPacket[4] = (byte) (compressed ? 1 : 0);
        encodedPacket[5] = (byte) codecType.ordinal();
        encodedPacket[6] = (byte) (packetId >> 8);
        encodedPacket[7] = (byte) packetId;
        System.arraycopy(payload, 0, encodedPacket, 8, payload.length);
        return encodedPacket;
    }

    private void validateWireFrame(byte[] data) {
        if (data == null || data.length < 8) {
            throw new PacketCodingException("Packet too short");
        }
        if (data.length > SharedConfig.MAX_PACKET_SIZE) {
            throw new PacketCodingException("Packet too large: " + data.length + " > " + SharedConfig.MAX_PACKET_SIZE);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("PacketPipeline is closed");
        }
    }

    private void closeForProtocolViolation() {
        try {
            closeHook.run();
        } finally {
            close();
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (dispatchLock) {
            synchronized (encodeLock) {
                synchronized (decodeLock) {
                    try {
                        distributor.close();
                    } catch (Exception e) {
                        log.error("Error while closing distributor", e);
                    }
                    if (cryptor != null) {
                        try {
                            cryptor.close();
                        } catch (Exception e) {
                            log.error("Error while closing cryptor", e);
                        }
                    }
                }
            }
        }
    }

    private static final class ProtocolCloseSignal extends RuntimeException {
    }
}
