package io.lolyay.gma4j.net.codec;

import io.lolyay.gma4j.net.codec.connection.ConnectionSettings;
import io.lolyay.gma4j.net.codec.encryption.PacketCryptor;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.codec.packet.PacketType;
import io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor;
import io.lolyay.gma4j.net.shared.CodecType;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.util.ByteReader;
import io.lolyay.gma4j.net.util.ByteWriter;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.DataFormatException;

@Slf4j
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
    private final Supplier<Boolean> authGate;
    @Getter
    private final ConnectionSettings settings;

    public PacketPipeline(Runnable closeHook, IPacketDistributor distributor) {
        this(closeHook, distributor, new ConnectionSettings());
    }

    public PacketPipeline(Runnable closeHook, IPacketDistributor distributor, ConnectionSettings settings) {
        this(closeHook, distributor, settings, () -> true);
    }

    public PacketPipeline(Runnable closeHook, IPacketDistributor distributor, ConnectionSettings settings,
                          Supplier<Boolean> authGate) {
        this.closeHook = closeHook;
        this.distributor = distributor;
        this.settings = settings;
        this.authGate = authGate;
    }

    public <T extends GMAPacket<T>> byte[] encode(T packet) {
        return encode(packet, false);
    }

    public <T extends GMAPacket<T>> byte[] encode(T packet, boolean urgent) {
        synchronized (encodeLock) {
            requireOpen();
            int limit = settings.sendLimit();
            byte[] encoded = encodePacket(packet, sequence, urgent, limit);
            if (cryptor != null) {
                encoded = cryptor.encrypt(encoded);
            }
            if (encoded.length > limit) {
                throw new PacketCodingException("Packet too large: " + encoded.length + " > " + limit);
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

        int flags = data[4] & 0xFF;
        if ((flags & PacketFlags.RESERVED_MASK) != 0) {
            throw new PacketCodingException("Reserved header flag bits set: 0x" + Integer.toHexString(flags));
        }
        boolean compressed = (flags & PacketFlags.COMPRESSED) != 0;
        boolean big = (flags & PacketFlags.BIG) != 0;
        boolean urgent = (flags & PacketFlags.URGENT) != 0;
        if (data.length > settings.getBasePacketSize() && !big) {
            throw new PacketCodingException("Oversized packet of " + data.length + " bytes without big flag");
        }
        if (big && !settings.isBigReceiveAllowed()) {
            throw new PacketCodingException("Big packet without granted big size mode");
        }
        if (urgent && !settings.isUrgentReceiveAllowed()) {
            throw new PacketCodingException("Urgent flag without granted low latency mode");
        }
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
        // app payloads stay opaque until the peer is authenticated
        if (!packetType.isSystem() && !authGate.get()) {
            log.warn("Application packet {} before authentication, closing", packetId);
            throw new ProtocolCloseSignal();
        }
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
            int plaintextLimit = (big ? settings.receiveLimit() : settings.getBasePacketSize()) - 8;
            try {
                payload = CompressionUtil.decompress(payload, plaintextLimit);
            } catch (DataFormatException e) {
                throw new PacketCodingException("Error while decompressing packet", e);
            }
            log.debug("Decompressed incoming packet {}: {} -> {} bytes", packetId, compressedLength, payload.length);
        }

        // the flag must match the plaintext size the sender saw
        if (big != (payload.length + 8 > settings.getBasePacketSize())) {
            throw new PacketCodingException(big
                    ? "Big flag on plaintext of " + (payload.length + 8) + " bytes"
                    : "Oversized plaintext of " + (payload.length + 8) + " bytes without big flag");
        }

        try {
            return packetType.codec().deserialize(payload, codecType);
        } catch (Exception e) {
            throw new PacketCodingException("Error decoding packet: " + packetId, e);
        }
    }

    private <T extends GMAPacket<T>> byte[] encodePacket(T packet, int currentSequence, boolean urgent, int limit) throws PacketCodingException {
        byte[] payload;
        boolean compressed = false;
        CodecType codecType = packet.getPacketType().codec().getCodecType();
        int packetId = packet.getPacketType().numericId();

        try {
            payload = packet.getPacketType().codec().serialize(packet);
        } catch (Exception e) {
            throw new PacketCodingException("Error encoding packet: " + packetId, e);
        }
        // plus header, so the peer can always decompress back to this size
        if (payload.length + 8 > limit) {
            throw new PacketCodingException("Packet plaintext too large: " + (payload.length + 8)
                    + " > " + limit);
        }
        // BIG reflects the plaintext size, decided before compression can shrink it
        boolean big = payload.length + 8 > settings.getBasePacketSize();

        // low latency trades bandwidth for the compression stall
        if (!settings.isLowLatency()
                && payload.length >= SharedConfig.PACKET_COMPRESSION_THRESHOLD
                && SharedConfig.PACKET_COMPRESSION_ENABLED) {
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

        int flags = compressed ? PacketFlags.COMPRESSED : 0;
        if (big) {
            flags |= PacketFlags.BIG;
        }
        if (urgent && settings.isLowLatency()) {
            flags |= PacketFlags.URGENT;
        }

        byte[] encodedPacket = new byte[payload.length + 8];
        ByteWriter.writeInt(encodedPacket, currentSequence, 0);
        encodedPacket[4] = (byte) flags;
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
        int limit = settings.receiveLimit();
        if (data.length > limit) {
            throw new PacketCodingException("Packet too large: " + data.length + " > " + limit);
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
