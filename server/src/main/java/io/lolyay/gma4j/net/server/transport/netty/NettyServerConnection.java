package io.lolyay.gma4j.net.server.transport.netty;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.connection.OutboundBudget;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;

import java.util.concurrent.atomic.AtomicBoolean;

public class NettyServerConnection implements MessageSender {
    private final Channel channel;
    private final OutboundBudget outboundBudget;
    private final AtomicBoolean flushPending = new AtomicBoolean();
    private volatile boolean coalesceFlush;
    private volatile boolean closing;

    public NettyServerConnection(Channel channel) {
        this.channel = channel;
        this.outboundBudget = new OutboundBudget(
                SharedConfig.MAX_PENDING_OUTBOUND_BYTES,
                SharedConfig.MAX_PENDING_OUTBOUND_PACKETS);
    }

    @Override
    public boolean send(byte[] data) {
        return send(data, false);
    }

    @Override
    public boolean send(byte[] data, boolean urgent) {
        if (closing || !channel.isActive()) {
            return false;
        }
        OutboundBudget.Reservation reservation = outboundBudget.tryReserve(
                OutboundBudget.protobufFrameBytes(data.length));
        if (reservation == null) {
            close();
            return false;
        }
        boolean coalesced = coalesceFlush && !urgent;
        if (coalesced && !channel.eventLoop().inEventLoop()) {
            try {
                channel.eventLoop().execute(() -> write(data, reservation, true));
                return true;
            } catch (RuntimeException failure) {
                reservation.close();
                close();
                return false;
            }
        }
        return write(data, reservation, coalesced);
    }

    private boolean write(byte[] data, OutboundBudget.Reservation reservation, boolean coalesced) {
        if (closing || !channel.isActive()) {
            reservation.close();
            return false;
        }
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        boolean submitted = false;
        try {
            ChannelFuture future = coalesced ? channel.write(buffer) : channel.writeAndFlush(buffer);
            submitted = true;
            future.addListener(completed -> {
                reservation.close();
                if (!completed.isSuccess()) {
                    close();
                }
            });
            if (coalesced && flushPending.compareAndSet(false, true)) {
                channel.eventLoop().execute(() -> {
                    flushPending.set(false);
                    channel.flush();
                });
            }
            return !future.isDone() || future.isSuccess();
        } catch (RuntimeException failure) {
            reservation.close();
            if (!submitted && buffer.refCnt() > 0) {
                buffer.release();
            }
            close();
            return false;
        }
    }

    @Override
    public void applyModes(boolean lowLatency, boolean bigSize) {
        coalesceFlush = bigSize && !lowLatency;
        channel.config().setOption(ChannelOption.TCP_NODELAY, lowLatency);
        channel.config().setWriteBufferWaterMark(bigSize
                ? new WriteBufferWaterMark(1 << 20, 8 << 20)
                : WriteBufferWaterMark.DEFAULT);
    }

    @Override
    public void close() {
        closing = true;
        channel.close();
    }
}
