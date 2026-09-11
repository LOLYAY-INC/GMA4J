package io.lolyay.gma4j.net.transport.netty;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;

import java.util.concurrent.atomic.AtomicBoolean;

public class NettyClientConnection implements MessageSender {

    private final Channel channel;
    private final AtomicBoolean flushPending = new AtomicBoolean();
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
        if (!channel.isActive()) {
            return false;
        }
        if (urgent || !coalesceFlush) {
            channel.writeAndFlush(Unpooled.wrappedBuffer(data));
        } else {
            channel.write(Unpooled.wrappedBuffer(data));
            scheduleFlush();
        }
        return true;
    }

    @Override
    public void applyModes(boolean lowLatency, boolean bigSize) {
        coalesceFlush = bigSize && !lowLatency;
        channel.config().setOption(ChannelOption.TCP_NODELAY, lowLatency);
        channel.config().setWriteBufferWaterMark(bigSize
                ? new WriteBufferWaterMark(1 << 20, 8 << 20)
                : WriteBufferWaterMark.DEFAULT);
    }

    private void scheduleFlush() {
        if (flushPending.compareAndSet(false, true)) {
            channel.eventLoop().execute(() -> {
                flushPending.set(false);
                channel.flush();
            });
        }
    }

    @Override
    public void close() {
        channel.close();
    }
}
