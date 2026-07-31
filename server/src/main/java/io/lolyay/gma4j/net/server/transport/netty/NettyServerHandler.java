package io.lolyay.gma4j.net.server.transport.netty;

import io.lolyay.gma4j.net.codec.connection.server.ServerClientHandler;
import io.lolyay.gma4j.net.codec.connection.server.ServerConnectionListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class NettyServerHandler extends ChannelInboundHandlerAdapter {

    private final ServerClientHandler clientHandler;
    private ServerConnectionListener listener;

    public NettyServerHandler(ServerClientHandler clientHandler) {
        this.clientHandler = clientHandler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        String remoteId = String.valueOf(ctx.channel().remoteAddress());
        listener = clientHandler.getOrCreateClient(remoteId);
        listener.onConnectionEstablished(new NettyServerConnection(ctx.channel()));
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        try {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            if (listener != null) {
                listener.onConnectionReceive(data);
            }
        } finally {
            buf.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (listener != null) {
            listener.onConnectionClosed("channel inactive");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (listener != null) {
            listener.onConnectionError(cause);
        }
        ctx.close();
    }
}
