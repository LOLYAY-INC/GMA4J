package io.lolyay.gma4j.net.codec.encryption.server;

import java.security.PrivateKey;

public interface IServerCertificateProvider {

    byte[] getCertificate();

    PrivateKey getSigningKey();
}
