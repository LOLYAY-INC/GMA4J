package io.lolyay.gma4j.net.transport.netty;

import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;
import io.lolyay.gma4j.net.transport.IClientTransport;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;

import java.net.URI;

public class NettyClientTransport implements IClientTransport {

    private final ClientConnectionListener listener;
    private EventLoopGroup group;
    private volatile Channel channel;

    public NettyClientTransport(ClientConnectionListener listener) {
        this.listener = listener;
    }

    @Override
    public void connect(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        group = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("frameDecoder", new ProtobufVarint32FrameDecoder())
                                .addLast("frameEncoder", new ProtobufVarint32LengthFieldPrepender())
                                .addLast("handler", new NettyClientHandler(listener));
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
            } else {
                listener.onConnectionError(future.cause());
                group.shutdownGracefully();
            }
        });
    }

    @Override
    public void close() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }
}
