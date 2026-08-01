package io.lolyay.gma4j.it;

import io.lolyay.gma4j.net.codec.CodecRegistry;
import io.lolyay.gma4j.net.codec.auth.server.GmaApiECCAuthServer;
import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import io.lolyay.gma4j.net.codec.packet.GMAPacket;
import io.lolyay.gma4j.net.server.GMA4JServer;
import io.lolyay.gma4j.net.server.ServerBindInfo;
import io.lolyay.gma4j.net.server.ServerEventHandler;
import io.lolyay.gma4j.net.server.net.ClientOnServer;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

public class WsEchoServer {

    public static void main(String[] args) throws Exception {
        CodecRegistry.getInstance().addCodec(ComplexPacketC2s.TYPE);
        CodecRegistry.getInstance().addCodec(ComplexPacketS2c.TYPE);

        KeyPair hostKey = generateEc();
        IServerCertificateProvider certProvider = new IServerCertificateProvider() {
            @Override
            public byte[] getCertificate() {
                return hostKey.getPublic().getEncoded();
            }

            @Override
            public PrivateKey getSigningKey() {
                return hostKey.getPrivate();
            }
        };

        KeyPair clientAuthKey = generateEc();
        String privatePkcs8 = Base64.getEncoder().encodeToString(clientAuthKey.getPrivate().getEncoded());
        String publicX509 = Base64.getEncoder().encodeToString(clientAuthKey.getPublic().getEncoded());

        ServerEventHandler handler = new ServerEventHandler() {
            @Override
            public boolean handle(ClientOnServer client, GMAPacket<?> packet) {
                if(packet instanceof ComplexPacketC2s c2s) {
                    ComplexBody body = c2s.body();
                    System.out.println("[echo] " + client.getClaimedClientId()
                            + " id=" + body.id()
                            + " tags=" + body.tags().size()
                            + " metrics=" + body.metrics().size()
                            + " items=" + body.inventory().size()
                            + " payload=" + body.payload().length + "B");
                    client.send(new ComplexPacketS2c(body));
                    return true;
                }
                return false;
            }

            @Override
            public void onClientAuthenticated(ClientOnServer client) {
                System.out.println("[auth] " + client.getClaimedClientId() + " (" + client.getAssignedId() + ")");
            }
        };

        GMA4JServer server = new GMA4JServer(handler, certProvider);
        server.start(new ServerBindInfo("127.0.0.1", 9000, "ws",
                new GmaApiECCAuthServer((ECPublicKey) clientAuthKey.getPublic())));

        System.out.println("==================================================================");
        System.out.println("GMA4J echo server on ws://127.0.0.1:9000  (ECDSA auth, ComplexPacket)");
        System.out.println("packet ids: ComplexPacketC2s=" + ComplexPacketC2s.TYPE.numericId()
                + "  ComplexPacketS2c=" + ComplexPacketS2c.TYPE.numericId());
        System.out.println("CLIENT ECC PRIVATE KEY (PKCS#8, base64) -> use in the JS client:");
        System.out.println(privatePkcs8);
        System.out.println("(server holds the matching public key: " + publicX509 + ")");
        System.out.println("==================================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        Thread.currentThread().join();
    }

    private static KeyPair generateEc() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }
}
