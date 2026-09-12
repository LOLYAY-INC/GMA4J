package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.connection.InboundBudget;
import io.lolyay.gma4j.net.codec.connection.MessageSender;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.net.ClientOnServer;
import io.lolyay.gma4j.net.server.net.GMA4JNetServer;
import io.lolyay.gma4j.net.shared.SharedConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundBudgetTest {

    private final long originalBudget = SharedConfig.MAX_INBOUND_PROCESSING_BYTES;

    @AfterEach
    void restoreConfig() {
        SharedConfig.MAX_INBOUND_PROCESSING_BYTES = originalBudget;
    }

    @Test
    void budgetIsHeldForTheDispatchAndReleasedAfter() {
        InboundBudget budget = new InboundBudget(() -> 16);
        assertTrue(budget.tryAcquire(10));
        assertFalse(budget.tryAcquire(7));
        assertTrue(budget.tryAcquire(6));
        budget.release(10);
        assertEquals(6, budget.inFlightBytes());
        budget.release(6);
        assertEquals(0, budget.inFlightBytes());
    }

    @Test
    void frameOverTheServerBudgetDropsTheConnection() throws Exception {
        SharedConfig.MAX_INBOUND_PROCESSING_BYTES = 8;
        GMA4JNetServer server = server();
        ClientOnServer client = new ClientOnServer(server, "budget-client");
        client.onConnectionEstablished(new MessageSender() {
            @Override
            public boolean send(byte[] data) {
                return true;
            }

            @Override
            public void close() {
            }
        });
        assertTrue(client.isConnected());

        // a frame inside the budget is accounted while it is decoded, then released
        client.onConnectionReceive(new byte[8]);
        assertEquals(0, server.getInboundBudget().inFlightBytes());
        assertTrue(client.isConnected());

        client.onConnectionReceive(new byte[9]);
        assertFalse(client.isConnected());
        assertEquals(0, server.getInboundBudget().inFlightBytes());
    }

    private static GMA4JNetServer server() throws Exception {
        return new GMA4JNetServer(new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                return false;
            }
        }, hostKey(), Map.of(), CodecRegistry.getInstance());
    }

    private static IServerCertificateProvider hostKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        return new IServerCertificateProvider() {
            @Override
            public byte[] getCertificate() {
                return pair.getPublic().getEncoded();
            }

            @Override
            public PrivateKey getSigningKey() {
                return pair.getPrivate();
            }
        };
    }
}
