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

import java.util.concurrent.CompletableFuture;
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
        return dispatch(data, urgent, null);
    }

    @Override
    public CompletableFuture<Void> sendWithCompletion(byte[] data, boolean urgent) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        dispatch(data, urgent, done);
        return done;
    }

    private boolean dispatch(byte[] data, boolean urgent, CompletableFuture<Void> done) {
        if (closing || !channel.isActive()) {
            fail(done, new IllegalStateException("Channel is not active"));
            return false;
        }
        OutboundBudget.Reservation reservation = outboundBudget.tryReserve(
                OutboundBudget.protobufFrameBytes(data.length));
        if (reservation == null) {
            close();
            fail(done, new IllegalStateException("Outbound budget exhausted"));
            return false;
        }
        boolean coalesced = coalesceFlush && !urgent;
        if (coalesced && !channel.eventLoop().inEventLoop()) {
            try {
                channel.eventLoop().execute(() -> write(data, reservation, true, done));
                return true;
            } catch (RuntimeException failure) {
                reservation.close();
                close();
                fail(done, failure);
                return false;
            }
        }
        return write(data, reservation, coalesced, done);
    }

    private boolean write(byte[] data, OutboundBudget.Reservation reservation, boolean coalesced, CompletableFuture<Void> done) {
        if (closing || !channel.isActive()) {
            reservation.close();
            fail(done, new IllegalStateException("Channel is not active"));
            return false;
        }
        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        boolean submitted = false;
        try {
            ChannelFuture future = coalesced ? channel.write(buffer) : channel.writeAndFlush(buffer);
            submitted = true;
            future.addListener(completed -> {
                reservation.close();
                if (completed.isSuccess()) {
                    if (done != null) {
                        done.complete(null);
                    }
                } else {
                    close();
                    fail(done, completed.cause());
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
            fail(done, failure);
            return false;
        }
    }

    private static void fail(CompletableFuture<Void> done, Throwable cause) {
        if (done != null) {
            done.completeExceptionally(cause);
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
