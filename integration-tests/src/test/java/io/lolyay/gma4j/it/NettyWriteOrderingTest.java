package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.transport.netty.NettyClientConnection;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyWriteOrderingTest {

    /**
     * A send issued from an event-loop callback must not overtake a write another thread
     * already queued with an earlier sequence number. The synchronous CapturingSender used
     * elsewhere cannot exercise this because it never touches the event loop.
     */
    @Test
    void eventLoopSendDoesNotOvertakeQueuedWrite() throws Exception {
        EventLoopGroup group = new DefaultEventLoopGroup(1);
        List<Integer> written = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch bothWritten = new CountDownLatch(2);
        LocalAddress address = new LocalAddress("gma4j-order-" + UUID.randomUUID());
        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap()
                    .group(group)
                    .channel(LocalServerChannel.class)
                    .childHandler(new SimpleChannelInboundHandler<Object>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                        }
                    });
            Channel server = serverBootstrap.bind(address).sync().channel();

            Bootstrap clientBootstrap = new Bootstrap()
                    .group(group)
                    .channel(LocalChannel.class)
                    .handler(new ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                            ByteBuf frame = (ByteBuf) msg;
                            written.add(frame.getInt(frame.readerIndex()));
                            bothWritten.countDown();
                            ctx.write(msg, promise);
                        }
                    });
            Channel client = clientBootstrap.connect(address).sync().channel();
            NettyClientConnection connection = new NettyClientConnection(client);

            CountDownLatch eventLoopBusy = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // hold the event loop so the external send below is queued behind it,
            // then send from the event loop after releasing
            client.eventLoop().execute(() -> {
                eventLoopBusy.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                connection.send(frame(1), false);
            });

            assertTrue(eventLoopBusy.await(5, TimeUnit.SECONDS));
            connection.send(frame(0), false);
            release.countDown();

            assertTrue(bothWritten.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(0, 1), written);

            server.close();
            client.close();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static byte[] frame(int index) {
        return new byte[]{
                (byte) (index >>> 24),
                (byte) (index >>> 16),
                (byte) (index >>> 8),
                (byte) index
        };
    }
}
