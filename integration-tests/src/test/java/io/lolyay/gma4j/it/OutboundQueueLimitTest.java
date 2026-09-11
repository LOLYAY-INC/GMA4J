package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.client.transport.ServerConnection;
import io.lolyay.gma4j.net.codec.PacketPipeline;
import io.lolyay.gma4j.net.codec.connection.IConnectionStateCallback;
import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.connection.OutboundBudget;
import io.lolyay.gma4j.net.codec.connection.WebSocketOutboundBudget;
import io.lolyay.gma4j.net.server.transport.netty.NettyServerConnection;
import io.lolyay.gma4j.net.server.transport.ws.WsServerConnection;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.transport.netty.NettyClientConnection;
import io.lolyay.gma4j.net.transport.ws.WsClientConnection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.enums.Role;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundQueueLimitTest {

    private final int originalMaxPacketSize = SharedConfig.MAX_PACKET_SIZE;
    private final int originalMaxPendingBytes = SharedConfig.MAX_PENDING_OUTBOUND_BYTES;
    private final int originalMaxPendingPackets = SharedConfig.MAX_PENDING_OUTBOUND_PACKETS;

    @AfterEach
    void restoreConfig() {
        SharedConfig.MAX_PACKET_SIZE = originalMaxPacketSize;
        SharedConfig.MAX_PENDING_OUTBOUND_BYTES = originalMaxPendingBytes;
        SharedConfig.MAX_PENDING_OUTBOUND_PACKETS = originalMaxPendingPackets;
    }

    @Test
    void nettyConnectionsEnforceExactFramedByteBoundary() {
        SharedConfig.MAX_PENDING_OUTBOUND_BYTES = 4;
        SharedConfig.MAX_PENDING_OUTBOUND_PACKETS = 10;

        for (Function<Channel, MessageSender> factory : nettyConnections()) {
            HoldingOutboundHandler handler = new HoldingOutboundHandler();
            EmbeddedChannel channel = new EmbeddedChannel(handler);
            try {
                MessageSender sender = factory.apply(channel);
                assertTrue(sender.send(new byte[1]));
                assertTrue(sender.send(new byte[1]));
                assertFalse(sender.send(new byte[1]));
                assertEquals(2, handler.size());
                assertFalse(channel.isActive());
            } finally {
                handler.releaseAll();
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void coalescedAndUrgentWritesShareTheSameLimit() {
        SharedConfig.MAX_PENDING_OUTBOUND_BYTES = 4;
        SharedConfig.MAX_PENDING_OUTBOUND_PACKETS = 2;
        for (Function<Channel, MessageSender> factory : nettyConnections()) {
            for (boolean urgent : new boolean[]{false, true}) {
                HoldingOutboundHandler handler = new HoldingOutboundHandler();
                EmbeddedChannel channel = new EmbeddedChannel(handler);
                try {
                    MessageSender sender = factory.apply(channel);
                    sender.applyModes(false, true);
                    assertTrue(sender.send(new byte[1], urgent));
                    assertTrue(sender.send(new byte[1], urgent));
                    channel.runPendingTasks();
                    assertEquals(2, handler.size());
                    assertFalse(sender.send(new byte[1], urgent));
                    assertFalse(channel.isActive());
                } finally {
                    handler.releaseAll();
                    channel.finishAndReleaseAll();
                }
            }
        }
    }

    @Test
    void nettyConnectionsReleaseCompletedReservations() {
        SharedConfig.MAX_PENDING_OUTBOUND_BYTES = 2;
        SharedConfig.MAX_PENDING_OUTBOUND_PACKETS = 1;

        for (Function<Channel, MessageSender> factory : nettyConnections()) {
            HoldingOutboundHandler handler = new HoldingOutboundHandler();
            EmbeddedChannel channel = new EmbeddedChannel(handler);
            try {
                MessageSender sender = factory.apply(channel);
                assertTrue(sender.send(new byte[1]));
                handler.succeedNext();
                channel.runPendingTasks();
                assertTrue(sender.send(new byte[1]));
                assertEquals(1, handler.size());
            } finally {
                handler.releaseAll();
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void nettyConnectionsCloseOnAsynchronousWriteFailure() {
        SharedConfig.MAX_PENDING_OUTBOUND_BYTES = 64;
        SharedConfig.MAX_PENDING_OUTBOUND_PACKETS = 4;

        for (Function<Channel, MessageSender> factory : nettyConnections()) {
            HoldingOutboundHandler handler = new HoldingOutboundHandler();
            EmbeddedChannel channel = new EmbeddedChannel(handler);
            try {
                MessageSender sender = factory.apply(channel);
                assertTrue(sender.send(new byte[1]));
                handler.failNext();
                channel.runPendingTasks();
                assertFalse(channel.isActive());
            } finally {
                handler.releaseAll();
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void outboundReservationsAreAtomicUnderConcurrency() throws Exception {
        int limit = 32;
        OutboundBudget budget = new OutboundBudget(limit, limit);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OutboundBudget.Reservation>> attempts = new ArrayList<>();
        try {
            for (int index = 0; index < limit * 2; index++) {
                attempts.add(executor.submit(() -> {
                    start.await();
                    return budget.tryReserve(1);
                }));
            }
            start.countDown();
            List<OutboundBudget.Reservation> accepted = new ArrayList<>();
            for (Future<OutboundBudget.Reservation> attempt : attempts) {
                OutboundBudget.Reservation reservation = attempt.get(5, TimeUnit.SECONDS);
                if (reservation != null) {
                    accepted.add(reservation);
                }
            }
            assertEquals(limit, accepted.size());
            assertEquals(limit, budget.pendingBytes());
            assertEquals(limit, budget.pendingPackets());
            accepted.forEach(OutboundBudget.Reservation::close);
            assertEquals(0, budget.pendingBytes());
            assertEquals(0, budget.pendingPackets());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void webSocketConnectionsEnforceQueueCapacityAndCloseImmediately() throws Exception {
        SharedConfig.MAX_PACKET_SIZE = 8;
        SharedConfig.MAX_PENDING_OUTBOUND_PACKETS = 3;

        SharedConfig.MAX_PENDING_OUTBOUND_BYTES = 16;
        WebSocketImpl serverEngine = openServerEngine();
        WsServerConnection serverConnection = new WsServerConnection(serverEngine);
        assertTrue(serverConnection.send(new byte[1]));
        assertTrue(serverConnection.send(new byte[1]));
        assertEquals(2, serverEngine.outQueue.size());
        assertFalse(serverConnection.send(new byte[1]));
        assertTrue(serverEngine.isClosed());

        SharedConfig.MAX_PENDING_OUTBOUND_BYTES = 28;
        TestWebSocketClient client = openClient();
        WebSocketImpl clientEngine = (WebSocketImpl) client.getConnection();
        WsClientConnection clientConnection = new WsClientConnection(client);
        assertTrue(clientConnection.send(new byte[1]));
        assertTrue(clientConnection.send(new byte[1]));
        assertEquals(2, clientEngine.outQueue.size());
        assertFalse(clientConnection.send(new byte[1]));
        assertTrue(clientEngine.isClosed());
    }

    @Test
    void webSocketBudgetRejectsImpossibleConfiguration() {
        LinkedBlockingQueue<ByteBuffer> queue = new LinkedBlockingQueue<>();

        IllegalArgumentException countFailure = assertThrows(IllegalArgumentException.class,
                () -> new WebSocketOutboundBudget(queue, 100, 1, 8, false));
        assertTrue(countFailure.getMessage().contains("MAX_PENDING_OUTBOUND_PACKETS"));

        IllegalArgumentException byteFailure = assertThrows(IllegalArgumentException.class,
                () -> new WebSocketOutboundBudget(queue, 11, 2, 8, false));
        assertTrue(byteFailure.getMessage().contains("MAX_PENDING_OUTBOUND_BYTES"));

        IllegalArgumentException packetFailure = assertThrows(IllegalArgumentException.class,
                () -> new WebSocketOutboundBudget(queue, 100, 2, 0, false));
        assertTrue(packetFailure.getMessage().contains("MAX_PACKET_SIZE"));
    }

    @Test
    void clientServerConnectionClosesRejectedAndThrowingSenders() throws Exception {
        RejectingSender rejected = new RejectingSender(false, null);
        ServerConnection rejectedConnection = connectedClient(rejected, new NoopConnectionStateCallback());
        rejectedConnection.send(new ClientGreetingPacket("rejected", 1));
        assertTrue(rejected.closed);
        assertFalse(rejectedConnection.isConnected());

        IllegalStateException sendFailure = new IllegalStateException("write failed");
        RejectingSender throwing = new RejectingSender(true, sendFailure);
        ServerConnection throwingConnection = connectedClient(throwing, new NoopConnectionStateCallback());
        IllegalStateException actualFailure = assertThrows(IllegalStateException.class,
                () -> throwingConnection.send(new ClientGreetingPacket("throwing", 2)));
        assertSame(sendFailure, actualFailure);
        assertTrue(throwing.closed);
        assertFalse(throwingConnection.isConnected());
    }

    @Test
    void clientCloseCallbackObservesDisconnectedState() throws Exception {
        ObservingConnectionStateCallback callback = new ObservingConnectionStateCallback();
        ServerConnection connection = connectedClient(new RejectingSender(true, null), callback);
        callback.connection = connection;

        connection.onConnectionClosed("closed");

        assertTrue(callback.sawDisconnectedState);
    }

    private static List<Function<Channel, MessageSender>> nettyConnections() {
        return List.of(NettyClientConnection::new, NettyServerConnection::new);
    }

    private static WebSocketImpl openServerEngine() throws Exception {
        Draft_6455 draft = new Draft_6455();
        draft.setParseMode(Role.SERVER);
        WebSocketImpl engine = new WebSocketImpl(new SilentWebSocketListener(), draft);
        setField(engine, "role", Role.SERVER);
        setField(engine, "readyState", ReadyState.OPEN);
        return engine;
    }

    private static TestWebSocketClient openClient() throws Exception {
        TestWebSocketClient client = new TestWebSocketClient();
        setField(client.getConnection(), "readyState", ReadyState.OPEN);
        return client;
    }

    private static ServerConnection connectedClient(MessageSender sender,
                                                     IConnectionStateCallback stateCallback) throws Exception {
        PacketPipeline pipeline = new PacketPipeline(() -> {}, new NoopPacketDistributor());
        ServerConnection connection = new ServerConnection(null, pipeline, pipeline.getSettings(), stateCallback, "client", "test://server", 0L);
        setField(connection, "messageSender", sender);
        setField(connection, "isConnected", true);
        return connection;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record HeldWrite(ByteBuf buffer, ChannelPromise promise) {
    }

    private static final class HoldingOutboundHandler extends ChannelOutboundHandlerAdapter {
        private final ConcurrentLinkedQueue<HeldWrite> writes = new ConcurrentLinkedQueue<>();

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            writes.add(new HeldWrite((ByteBuf) message, promise));
        }

        int size() {
            return writes.size();
        }

        void succeedNext() {
            HeldWrite write = writes.remove();
            write.buffer().release();
            write.promise().setSuccess();
        }

        void failNext() {
            HeldWrite write = writes.remove();
            write.buffer().release();
            write.promise().setFailure(new IOException("controlled write failure"));
        }

        void releaseAll() {
            HeldWrite write;
            while ((write = writes.poll()) != null) {
                if (write.buffer().refCnt() > 0) {
                    write.buffer().release();
                }
                write.promise().tryFailure(new IOException("test cleanup"));
            }
        }
    }

    private static final class TestWebSocketClient extends WebSocketClient {
        private TestWebSocketClient() {
            super(URI.create("ws://localhost"), new Draft_6455());
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
        }

        @Override
        public void onMessage(String message) {
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception exception) {
        }
    }

    private static final class SilentWebSocketListener extends WebSocketAdapter {
        @Override
        public void onWebsocketMessage(WebSocket connection, String message) {
        }

        @Override
        public void onWebsocketMessage(WebSocket connection, ByteBuffer message) {
        }

        @Override
        public void onWebsocketOpen(WebSocket connection, Handshakedata handshake) {
        }

        @Override
        public void onWebsocketClose(WebSocket connection, int code, String reason, boolean remote) {
        }

        @Override
        public void onWebsocketClosing(WebSocket connection, int code, String reason, boolean remote) {
        }

        @Override
        public void onWebsocketCloseInitiated(WebSocket connection, int code, String reason) {
        }

        @Override
        public void onWebsocketError(WebSocket connection, Exception exception) {
        }

        @Override
        public void onWriteDemand(WebSocket connection) {
        }

        @Override
        public InetSocketAddress getLocalSocketAddress(WebSocket connection) {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress(WebSocket connection) {
            return null;
        }
    }

    private static final class NoopPacketDistributor implements io.lolyay.gma4j.net.codec.packetdistributer.IPacketDistributor {
        @Override
        public <T extends io.lolyay.gma4j.net.codec.packet.GMAPacket<T>> void distribute(T packet) {
        }
    }

    private static class NoopConnectionStateCallback implements IConnectionStateCallback {
        @Override
        public void onConnectionEstablished() {
        }

        @Override
        public void onConnectionClosed(String reason) {
        }

        @Override
        public void onConnectionError(Throwable error) {
        }

        @Override
        public void onAuthSuccess() {
        }
    }

    private static final class ObservingConnectionStateCallback extends NoopConnectionStateCallback {
        private ServerConnection connection;
        private boolean sawDisconnectedState;

        @Override
        public void onConnectionClosed(String reason) {
            sawDisconnectedState = !connection.isConnected();
        }
    }

    private static final class RejectingSender implements MessageSender {
        private final boolean result;
        private final RuntimeException failure;
        private boolean closed;

        private RejectingSender(boolean result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public boolean send(byte[] data) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
