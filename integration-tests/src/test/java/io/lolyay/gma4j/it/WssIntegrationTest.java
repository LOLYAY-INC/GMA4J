package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.client.ClientConnectionInfo;
import io.lolyay.gma4j.net.client.ClientEventHandler;
import io.lolyay.gma4j.net.client.GMA4JClient;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.client.ClientAuth;
import io.lolyay.gma4j.net.codec.auth.server.GmaNoAuthServer;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.server.GMA4JServer;
import io.lolyay.gma4j.net.server.ServerBindInfo;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.WsServerSecurity;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import io.lolyay.gma4j.net.transport.TransportManager;
import io.lolyay.gma4j.net.transport.ws.WsClientTransportFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WssIntegrationTest {

    private static final char[] PASSWORD = "changeit".toCharArray();

    @BeforeAll
    static void prepareProtocol() {
        CodecRegistry.getInstance().addCodec(ClientGreetingPacket.TYPE);
        CodecRegistry.getInstance().addCodec(ServerWelcomePacket.TYPE);
        TransportManager.registerClientFactory(new WsClientTransportFactory());
    }

    @Test
    void wssPacketRoundTripsOverTls() throws Exception {
        int port = freePort();
        URI uri = URI.create("wss://localhost:" + port);
        AtomicReference<ClientGreetingPacket> echo = new AtomicReference<>();
        CountDownLatch echoed = new CountDownLatch(1);

        GMA4JServer server = new GMA4JServer(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                if (packet instanceof ClientGreetingPacket greeting) {
                    client.send(greeting);
                    return true;
                }
                return false;
            }
        }, hostKey());
        server.start(new ServerBindInfo("localhost", port, "wss", new GmaNoAuthServer()),
                new WsServerSecurity(serverContext()));

        EchoClient handler = new EchoClient(echo, echoed);
        GMA4JClient client = new GMA4JClient(handler);
        handler.client = client;
        client.setSslContext(clientContext());

        try {
            client.connect(new ClientConnectionInfo("wss-client", uri, ClientAuth.none()));
            assertTrue(echoed.await(20, TimeUnit.SECONDS), "packet was not echoed over wss");
            assertNotNull(echo.get());
            assertEquals("hello-over-tls", echo.get().clientName());
            assertTrue(handler.error.get() == null, () -> "client error: " + handler.error.get());
        } finally {
            try {
                assertDoesNotThrow(client::disconnect);
            } finally {
                assertDoesNotThrow(server::stop);
            }
        }
    }

    @Test
    void originAllowlistGatesBrowserHandshakes() throws Exception {
        int port = freePort();
        GMA4JServer server = new GMA4JServer(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }
        }, hostKey());
        server.start(new ServerBindInfo("127.0.0.1", port, "ws", new GmaNoAuthServer()),
                new WsServerSecurity(null, Set.of("https://good.example")));

        try {
            assertEquals(101, handshakeStatus(port, "https://good.example"), "allowed origin must upgrade");
            assertEquals(101, handshakeStatus(port, null), "native client (no Origin) must upgrade");
            // Java-WebSocket aborts a rejected upgrade with HTTP 404 (no WebSocket, so no close frame)
            assertEquals(404, handshakeStatus(port, "https://evil.example"), "disallowed origin must be refused");
        } finally {
            assertDoesNotThrow(server::stop);
        }
    }

    @Test
    void clientSslContextIsIgnoredForPlaintextWs() throws Exception {
        int port = freePort();
        URI uri = URI.create("ws://127.0.0.1:" + port);
        AtomicReference<ClientGreetingPacket> echo = new AtomicReference<>();
        CountDownLatch echoed = new CountDownLatch(1);

        GMA4JServer server = new GMA4JServer(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                if (packet instanceof ClientGreetingPacket greeting) {
                    client.send(greeting);
                    return true;
                }
                return false;
            }
        }, hostKey());
        server.start(new ServerBindInfo("127.0.0.1", port, "ws", new GmaNoAuthServer()));

        EchoClient handler = new EchoClient(echo, echoed);
        GMA4JClient client = new GMA4JClient(handler);
        handler.client = client;
        // a configured SSL context must not force TLS onto a ws:// endpoint
        client.setSslContext(clientContext());

        try {
            client.connect(new ClientConnectionInfo("ws-client", uri, ClientAuth.none()));
            assertTrue(echoed.await(20, TimeUnit.SECONDS), "ws connection with an SSL context set failed");
            assertTrue(handler.error.get() == null, () -> "client error: " + handler.error.get());
        } finally {
            try {
                assertDoesNotThrow(client::disconnect);
            } finally {
                assertDoesNotThrow(server::stop);
            }
        }
    }

    /** Raw RFC 6455 upgrade so an arbitrary Origin header can be sent; returns the HTTP status code */
    private static int handshakeStatus(int port, String origin) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            StringBuilder request = new StringBuilder()
                    .append("GET / HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: ").append(Base64.getEncoder().encodeToString(new byte[16])).append("\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n");
            if (origin != null) {
                request.append("Origin: ").append(origin).append("\r\n");
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                line.append((char) c);
            }
            // status line: HTTP/1.1 <code> <reason>
            String[] parts = line.toString().trim().split(" ");
            return parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;
        }
    }

    private static SSLContext serverContext() throws Exception {
        KeyStore ks = loadKeystore();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static SSLContext clientContext() throws Exception {
        // trust just the self-signed leaf, taken out of the key entry into a fresh truststore
        KeyStore ks = loadKeystore();
        Certificate cert = ks.getCertificate("gma4j");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("gma4j", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore loadKeystore() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = WssIntegrationTest.class.getResourceAsStream("/gma4j-test.p12")) {
            assertNotNull(in, "gma4j-test.p12 test keystore is missing");
            ks.load(in, PASSWORD);
        }
        return ks;
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

    static final class EchoClient implements ClientEventHandler {
        private final AtomicReference<ClientGreetingPacket> echo;
        private final CountDownLatch echoed;
        final AtomicReference<Throwable> error = new AtomicReference<>();
        GMA4JClient client;

        EchoClient(AtomicReference<ClientGreetingPacket> echo, CountDownLatch echoed) {
            this.echo = echo;
            this.echoed = echoed;
        }

        @Override
        public <T extends GMAPacket<T>> boolean handle(T packet) {
            if (packet instanceof ClientGreetingPacket greeting) {
                echo.set(greeting);
                echoed.countDown();
                return true;
            }
            return false;
        }

        @Override
        public void onAuthSuccess() {
            client.send(new ClientGreetingPacket("hello-over-tls", 1));
        }

        @Override
        public void onConnectionEstablished() {
        }

        @Override
        public void onConnectionClosed(String reason) {
        }

        @Override
        public void onConnectionError(Throwable e) {
            error.compareAndSet(null, e);
        }
    }
}
