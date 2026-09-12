package io.lolyay.gma4j.net.server.transport.netty;

import io.lolyay.gma4j.net.codec.PacketCodingException;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Varint32 length prefixed frames with a per connection limit. A header
 * over the limit fails the decoder before any payload is cumulated and
 * everything after it is discarded while the close is in flight.
 */
public class LimitedVarint32FrameDecoder extends ByteToMessageDecoder {
    private static final int INCOMPLETE = -1;

    private final IntSupplier maxFrameSize;
    private boolean rejected;

    public LimitedVarint32FrameDecoder() {
        this(() -> SharedConfig.MAX_PACKET_SIZE);
    }

    public LimitedVarint32FrameDecoder(IntSupplier maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (rejected) {
            in.skipBytes(in.readableBytes());
            return;
        }
        int start = in.readerIndex();
        int length;
        try {
            length = readVarint32(in);
        } catch (CorruptedFrameException e) {
            rejected = true;
            throw e;
        }
        if (length == INCOMPLETE) {
            in.readerIndex(start);
            return;
        }

        int limit = maxFrameSize.getAsInt();
        if (length > limit) {
            rejected = true;
            throw new PacketCodingException("Packet too large; Size: %s, max: %s".formatted(length, limit));
        }
        if (in.readableBytes() < length) {
            in.readerIndex(start);
            return;
        }
        out.add(in.readRetainedSlice(length));
    }

    /** Varint32 below 2^31, at most 5 bytes, overflow bits are rejected */
    private static int readVarint32(ByteBuf in) {
        int result = 0;
        for (int shift = 0; shift < 32; shift += 7) {
            if (!in.isReadable()) {
                return INCOMPLETE;
            }
            byte next = in.readByte();
            if (shift == 28 && (next & 0xF8) != 0) {
                throw new CorruptedFrameException("malformed varint");
            }
            result |= (next & 0x7F) << shift;
            if (next >= 0) {
                return result;
            }
        }
        throw new CorruptedFrameException("malformed varint");
    }
}
