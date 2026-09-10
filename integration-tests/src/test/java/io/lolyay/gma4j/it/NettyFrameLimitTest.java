package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.shared.SharedConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyFrameLimitTest {

    @Test
    void bothDecodersUseInclusiveMaximum() {
        List<Supplier<ChannelHandler>> decoders = List.of(
                io.lolyay.gma4j.net.server.transport.netty.LimitedVarint32FrameDecoder::new,
                io.lolyay.gma4j.net.transport.netty.coder.LimitedVarint32FrameDecoder::new);

        for (Supplier<ChannelHandler> decoder : decoders) {
            assertAccepted(decoder, SharedConfig.MAX_PACKET_SIZE - 1);
            assertAccepted(decoder, SharedConfig.MAX_PACKET_SIZE);
            assertRejected(decoder, SharedConfig.MAX_PACKET_SIZE + 1);
        }
    }

    @Test
    void incompleteFrameWaitsForRemainingBytes() {
        for (Supplier<ChannelHandler> decoder : decoders()) {
            EmbeddedChannel channel = new EmbeddedChannel(decoder.get());
            ByteBuf frame = frame(16);
            ByteBuf first = frame.readRetainedSlice(5);
            try {
                assertFalse(channel.writeInbound(first));
                assertNull(channel.readInbound());
                assertTrue(channel.writeInbound(frame));
                ByteBuf decoded = channel.readInbound();
                try {
                    assertEquals(16, decoded.readableBytes());
                } finally {
                    decoded.release();
                }
            } finally {
                if (frame.refCnt() > 0) {
                    frame.release();
                }
                channel.finishAndReleaseAll();
            }
        }
    }

    private static List<Supplier<ChannelHandler>> decoders() {
        return List.of(
                io.lolyay.gma4j.net.server.transport.netty.LimitedVarint32FrameDecoder::new,
                io.lolyay.gma4j.net.transport.netty.coder.LimitedVarint32FrameDecoder::new);
    }

    private static void assertAccepted(Supplier<ChannelHandler> decoder, int length) {
        EmbeddedChannel channel = new EmbeddedChannel(decoder.get());
        try {
            assertTrue(channel.writeInbound(frame(length)));
            ByteBuf decoded = channel.readInbound();
            try {
                assertEquals(length, decoded.readableBytes());
            } finally {
                decoded.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertRejected(Supplier<ChannelHandler> decoder, int length) {
        EmbeddedChannel channel = new EmbeddedChannel(decoder.get());
        try {
            DecoderException error = assertThrows(DecoderException.class,
                    () -> channel.writeInbound(frame(length)));
            assertInstanceOf(io.lolyay.gma4j.net.codec.PacketCodingException.class, error.getCause());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ByteBuf frame(int length) {
        ByteBuf frame = Unpooled.buffer(varIntSize(length) + length);
        writeVarInt(frame, length);
        frame.writeZero(length);
        return frame;
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte(remaining);
    }

    private static int varIntSize(int value) {
        int bytes = 1;
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            bytes++;
            remaining >>>= 7;
        }
        return bytes;
    }
}
