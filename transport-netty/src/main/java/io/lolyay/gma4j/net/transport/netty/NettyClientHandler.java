package io.lolyay.gma4j.net.transport.netty;

import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class NettyClientHandler extends ChannelInboundHandlerAdapter {

    private final ClientConnectionListener listener;

    public NettyClientHandler(ClientConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        listener.onConnectionEstablished(new NettyClientConnection(ctx.channel()));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            listener.onConnectionReceive(data);
        } finally {
            buf.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        listener.onConnectionClosed("channel inactive");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        listener.onConnectionError(cause);
        ctx.close();
    }
}
