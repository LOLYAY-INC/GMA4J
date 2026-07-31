package io.lolyay.gma4j.net.server.cert;

import io.lolyay.gma4j.net.codec.encryption.server.IServerCertificateProvider;
import lombok.SneakyThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;

public class FileServerCertificateProvider implements IServerCertificateProvider {

    private static final String KEY_ALGORITHM = "EC";
    private static final String CURVE = "secp256r1";
    private static final String PRIVATE_KEY_FILE = "host.key";
    private static final String PUBLIC_KEY_FILE = "host.pub";

    private final byte[] certificate;
    private final PrivateKey signingKey;

    @SneakyThrows
    public FileServerCertificateProvider(Path base) {
        Path privatePath = base.resolve(PRIVATE_KEY_FILE);
        Path publicPath = base.resolve(PUBLIC_KEY_FILE);

        if (Files.exists(privatePath) && Files.exists(publicPath)) {
            this.certificate = Files.readAllBytes(publicPath);
            this.signingKey = loadPrivateKey(Files.readAllBytes(privatePath));
        } else {
            KeyPair keyPair = generateHostKey();
            this.certificate = keyPair.getPublic().getEncoded();
            this.signingKey = keyPair.getPrivate();
            persist(base, privatePath, publicPath, keyPair);
        }
    }

    @Override
    public byte[] getCertificate() {
        return certificate.clone();
    }

    @Override
    public PrivateKey getSigningKey() {
        return signingKey;
    }

    private static KeyPair generateHostKey() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        kpg.initialize(new ECGenParameterSpec(CURVE));
        return kpg.generateKeyPair();
    }

    private static PrivateKey loadPrivateKey(byte[] pkcs8) throws GeneralSecurityException {
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private static void persist(Path base, Path privatePath, Path publicPath, KeyPair keyPair) throws IOException {
        Files.createDirectories(base);
        Files.write(privatePath, keyPair.getPrivate().getEncoded());
        Files.write(publicPath, keyPair.getPublic().getEncoded());
    }
}
