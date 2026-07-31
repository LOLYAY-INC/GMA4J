package io.lolyay.gma4j.net.server.transport.netty;

import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

public class NettyServerConnection implements MessageSender {

    private final Channel channel;

    public NettyServerConnection(Channel channel) {
        this.channel = channel;
    }

    @Override
    public boolean send(byte[] data) {
        if (!channel.isActive()) {
            return false;
        }
        channel.writeAndFlush(Unpooled.wrappedBuffer(data));
        return true;
    }

    @Override
    public void close() {
        channel.close();
    }
}
