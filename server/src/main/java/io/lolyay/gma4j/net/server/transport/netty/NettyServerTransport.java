package io.lolyay.gma4j.net.server.transport.netty;

import io.lolyay.gma4j.net.codec.connection.server.ServerClientHandler;
import io.lolyay.gma4j.net.transport.IServerTransport;
import io.lolyay.gma4j.net.transport.ServerTransportData;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;

public class NettyServerTransport implements IServerTransport {

    private final ServerTransportData data;
    private final ServerClientHandler clientHandler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile Channel serverChannel;

    public NettyServerTransport(ServerTransportData data, ServerClientHandler clientHandler) {
        this.data = data;
        this.clientHandler = clientHandler;
    }

    @Override
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("frameDecoder", new ProtobufVarint32FrameDecoder())
                                .addLast("frameEncoder", new ProtobufVarint32LengthFieldPrepender())
                                .addLast("handler", new NettyServerHandler(clientHandler));
                    }
                });

        serverChannel = bootstrap.bind(data.host(), data.port()).syncUninterruptibly().channel();
    }

    @Override
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }
}
