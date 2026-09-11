package io.lolyay.gma4j.net.server.transport.netty;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.connection.OutboundBudget;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

public class NettyServerConnection implements MessageSender {

    private final Channel channel;
    private final OutboundBudget outboundBudget;

    public NettyServerConnection(Channel channel) {
        this.channel = channel;
        this.outboundBudget = new OutboundBudget(
                SharedConfig.MAX_PENDING_OUTBOUND_BYTES,
                SharedConfig.MAX_PENDING_OUTBOUND_PACKETS);
    }

    @Override
    public boolean send(byte[] data) {
        if (!channel.isActive()) {
            return false;
        }
        OutboundBudget.Reservation reservation = outboundBudget.tryReserve(
                OutboundBudget.protobufFrameBytes(data.length));
        if (reservation == null) {
            channel.close();
            return false;
        }

        ByteBuf buffer = Unpooled.wrappedBuffer(data);
        ChannelFuture future;
        try {
            future = channel.writeAndFlush(buffer);
        } catch (RuntimeException failure) {
            reservation.close();
            if (buffer.refCnt() > 0) {
                buffer.release();
            }
            channel.close();
            return false;
        }

        try {
            future.addListener(completed -> {
                reservation.close();
                if (!completed.isSuccess()) {
                    channel.close();
                }
            });
        } catch (RuntimeException failure) {
            reservation.close();
            channel.close();
            return false;
        }
        return !future.isDone() || future.isSuccess();
    }

    @Override
    public void close() {
        channel.close();
    }
}
