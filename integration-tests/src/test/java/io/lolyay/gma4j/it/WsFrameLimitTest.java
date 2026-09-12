package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.auth.server.GmaNoAuthServer;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.server.GMA4JServer;
import io.lolyay.gma4j.net.server.ServerBindInfo;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketAdapter;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.WebSocketListener;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.enums.ReadyState;
import org.java_websocket.enums.Role;
import org.java_websocket.handshake.Handshakedata;
import org.java_websocket.protocols.Protocol;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Talks raw RFC 6455 so a frame header can be sent without its payload */
class WsFrameLimitTest {

    private static final int CLOSE_TOO_BIG = 1009;

    @Test
    void oversizedFrameHeaderIsRefusedBeforeThePayload() throws Exception {
        int port = freePort();
        GMA4JServer server = new GMA4JServer(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }
        }, hostKey());
        server.start(new ServerBindInfo("127.0.0.1", port, "ws", new GmaNoAuthServer()));

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            upgrade(out, in, port);

            // unauthenticated peers only get the base allowance, this claims twice that
            out.write(binaryFrameHeader((long) SharedConfig.MAX_PACKET_SIZE * 2));
            out.flush();

            assertEquals(CLOSE_TOO_BIG, readCloseCode(in));
        } finally {
            assertDoesNotThrow(server::stop);
        }
    }

    /** A grant dispatched from the same read must cover the big frame right behind it */
    @Test
    void limitRaisedByEarlierFrameAppliesWithinSameRead() throws Exception {
        for (boolean serverSide : new boolean[]{true, false}) {
            AtomicInteger limit = new AtomicInteger(16);
            List<Integer> seen = new ArrayList<>();
            WebSocketListener listener = new RecordingListener(message -> {
                seen.add(message.remaining());
                limit.set(1024);
            });
            Draft_6455 root = serverSide
                    ? new io.lolyay.gma4j.net.server.transport.ws.FrameGuardedDraft(
                            Collections.emptyList(), Collections.singletonList(new Protocol("")), 1 << 20)
                    : new io.lolyay.gma4j.net.transport.ws.FrameGuardedDraft(
                            Collections.emptyList(), Collections.singletonList(new Protocol("")), 1 << 20);
            WebSocketImpl engine = new WebSocketImpl(listener, root);
            if (serverSide) {
                setField(engine, "role", Role.SERVER);
                engine.getDraft().setParseMode(Role.SERVER);
            }
            setField(engine, "readyState", ReadyState.OPEN);
            bind(engine, limit);

            byte[] wire = concat(binaryFrame(8, serverSide), binaryFrame(512, serverSide));
            engine.decode(ByteBuffer.wrap(wire));

            assertEquals(List.of(8, 512), seen, serverSide ? "server draft" : "client draft");
            assertTrue(engine.isOpen());
        }
    }

    private static void bind(WebSocketImpl engine, AtomicInteger limit) {
        if (engine.getDraft() instanceof io.lolyay.gma4j.net.server.transport.ws.FrameGuardedDraft server) {
            server.bind(engine, limit::get);
        } else {
            ((io.lolyay.gma4j.net.transport.ws.FrameGuardedDraft) engine.getDraft()).bind(engine, limit::get);
        }
    }

    /** Frames from a client are masked, frames from a server are not */
    private static byte[] binaryFrame(int payload, boolean masked) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x82);
        int maskBit = masked ? 0x80 : 0;
        if (payload <= 125) {
            out.write(maskBit | payload);
        } else {
            out.write(maskBit | 126);
            out.write(payload >>> 8);
            out.write(payload);
        }
        if (masked) {
            out.writeBytes(new byte[4]);
        }
        byte[] body = new byte[payload]; // non zero so a misaligned guard reads it as a header
        Arrays.fill(body, (byte) 0xFF);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class RecordingListener extends WebSocketAdapter {
        private final Consumer<ByteBuffer> onMessage;

        private RecordingListener(Consumer<ByteBuffer> onMessage) {
            this.onMessage = onMessage;
        }

        @Override
        public void onWebsocketMessage(WebSocket conn, ByteBuffer message) {
            onMessage.accept(message);
        }

        @Override
        public void onWebsocketMessage(WebSocket conn, String message) {
        }

        @Override
        public void onWebsocketOpen(WebSocket conn, Handshakedata handshake) {
        }

        @Override
        public void onWebsocketClose(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onWebsocketClosing(WebSocket conn, int code, String reason, boolean remote) {
        }

        @Override
        public void onWebsocketCloseInitiated(WebSocket conn, int code, String reason) {
        }

        @Override
        public void onWebsocketError(WebSocket conn, Exception exception) {
        }

        @Override
        public void onWriteDemand(WebSocket conn) {
        }

        @Override
        public InetSocketAddress getLocalSocketAddress(WebSocket conn) {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress(WebSocket conn) {
            return null;
        }
    }

    private static void upgrade(OutputStream out, InputStream in, int port) throws Exception {
        byte[] nonce = new byte[16];
        String request = "GET / HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        ByteArrayOutputStream response = new ByteArrayOutputStream();
        while (!response.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
            int next = in.read();
            assertTrue(next >= 0, "handshake response truncated");
            response.write(next);
        }
        assertTrue(response.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 101"),
                () -> "unexpected handshake response: " + response);
    }

    private static byte[] binaryFrameHeader(long payloadLength) {
        byte[] header = new byte[14];
        header[0] = (byte) 0x82; // fin, binary
        header[1] = (byte) 0xFF; // masked, 64 bit length
        for (int i = 0; i < 8; i++) {
            header[2 + i] = (byte) (payloadLength >>> (56 - i * 8));
        }
        return header; // mask key stays zero
    }

    private static int readCloseCode(InputStream in) throws Exception {
        int first = in.read();
        assertEquals(0x88, first, "expected a close frame");
        int length = in.read() & 0x7F;
        assertTrue(length >= 2, "close frame without a status code");
        return (in.read() << 8) | in.read();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static IServerCertificateProvider hostKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keyPair = generator.generateKeyPair();
        return new IServerCertificateProvider() {
            @Override
            public byte[] getCertificate() {
                return keyPair.getPublic().getEncoded();
            }

            @Override
            public PrivateKey getSigningKey() {
                return keyPair.getPrivate();
            }
        };
    }
}
