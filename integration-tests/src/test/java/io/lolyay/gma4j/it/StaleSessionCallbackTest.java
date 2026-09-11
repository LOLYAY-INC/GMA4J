package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.client.ClientEventHandler;
import io.lolyay.gma4j.net.client.GMA4JClient;
import io.lolyay.gma4j.net.client.cert.NoOpCertificateKeeper;
import io.lolyay.gma4j.net.client.net.GMA4JNetClient;
import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.auth.client.GmaAuthClient;
import io.lolyay.gma4j.net.codec.connection.client.ClientConnectionListener;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.transport.IClientTransport;
import io.lolyay.gma4j.net.transport.IClientTransportFactory;
import io.lolyay.gma4j.net.transport.TransportManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Late transport callbacks from a replaced session must not tear down the current one. */
class StaleSessionCallbackTest {

    private static final List<ClientConnectionListener> LISTENERS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void registerFakeTransport() {
        TransportManager.registerClientFactory(new IClientTransportFactory() {
            @Override
            public Set<String> schemes() {
                return Set.of("faketest");
            }

            @Override
            public IClientTransport create(ClientConnectionListener listener) {
                LISTENERS.add(listener);
                return new IClientTransport() {
                    @Override
                    public void connect(URI uri) {
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        });
    }

    @Test
    void staleCloseIsIgnoredButCurrentCloseTearsDown() {
        LISTENERS.clear();
        GMA4JNetClient netClient = new GMA4JNetClient(
                new StubHandler(), "stale-client",
                Map.<GmaAuthType, GmaAuthClient>of(), new NoOpCertificateKeeper(),
                CodecRegistry.getInstance(), new GMA4JClient(new StubHandler()));

        netClient.connect("faketest://127.0.0.1:1");
        netClient.connect("faketest://127.0.0.1:1"); // replaces the first session

        ClientConnectionListener stale = LISTENERS.get(0);
        stale.onConnectionClosed("late callback from replaced session");

        // the current session is still open, so a real disconnect performs the transition
        assertTrue(netClient.disconnect(), "stale callback must not have closed the current session");
        assertFalse(netClient.disconnect(), "disconnect is idempotent once closed");
    }

    private static final class StubHandler implements ClientEventHandler {
        @Override
        public <T extends GMAPacket<T>> boolean handle(T packet) {
            return true;
        }

        @Override
        public void onConnectionEstablished() {
        }

        @Override
        public void onConnectionClosed(String reason) {
        }

        @Override
        public void onConnectionError(Throwable e) {
        }

        @Override
        public void onAuthSuccess() {
        }
    }
}
