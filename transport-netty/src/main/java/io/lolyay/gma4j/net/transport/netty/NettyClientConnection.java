package io.lolyay.gma4j.net.transport.netty;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class NettyClientConnection implements MessageSender {

    private final Channel channel;
    private final AtomicBoolean flushPending = new AtomicBoolean();
    private final AtomicLong queuedBytes = new AtomicLong();
    private volatile boolean coalesceFlush;

    public NettyClientConnection(Channel channel) {
        this.channel = channel;
    }

    @Override
    public boolean send(byte[] data) {
        return send(data, false);
    }

    @Override
    public boolean send(byte[] data, boolean urgent) {
        return !sendWithCompletion(data, urgent).isCompletedExceptionally();
    }

    @Override
    public CompletableFuture<Void> sendWithCompletion(byte[] data, boolean urgent) {
        if (!channel.isActive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Channel is not active"));
        }
        long queued = queuedBytes.addAndGet(data.length);
        if (queued > SharedConfig.MAX_QUEUED_WRITE_BYTES) {
            queuedBytes.addAndGet(-data.length);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Send queue full: " + queued + " > " + SharedConfig.MAX_QUEUED_WRITE_BYTES));
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        if (urgent || !coalesceFlush) {
            track(channel.writeAndFlush(Unpooled.wrappedBuffer(data)), data.length, done);
        } else if (channel.eventLoop().inEventLoop()) {
            writeCoalesced(data, done);
        } else {
            channel.eventLoop().execute(() -> writeCoalesced(data, done));
        }
        return done;
    }

    @Override
    public void applyModes(boolean lowLatency, boolean bigSize) {
        coalesceFlush = bigSize && !lowLatency;
        channel.config().setOption(ChannelOption.TCP_NODELAY, lowLatency);
        channel.config().setWriteBufferWaterMark(bigSize
                ? new WriteBufferWaterMark(1 << 20, 8 << 20)
                : WriteBufferWaterMark.DEFAULT);
    }

    /** Write and flush scheduling stay on the event loop so no write can miss its flush */
    private void writeCoalesced(byte[] data, CompletableFuture<Void> done) {
        track(channel.write(Unpooled.wrappedBuffer(data)), data.length, done);
        if (flushPending.compareAndSet(false, true)) {
            channel.eventLoop().execute(() -> {
                flushPending.set(false);
                channel.flush();
            });
        }
    }

    private void track(ChannelFuture future, int size, CompletableFuture<Void> done) {
        future.addListener(f -> {
            queuedBytes.addAndGet(-size);
            if (f.isSuccess()) {
                done.complete(null);
            } else {
                done.completeExceptionally(f.cause());
            }
        });
    }

    @Override
    public void close() {
        channel.close();
    }
}
