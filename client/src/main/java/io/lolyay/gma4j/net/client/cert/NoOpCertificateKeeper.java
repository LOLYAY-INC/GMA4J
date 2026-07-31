package io.lolyay.gma4j.net.client.cert;

import io.lolyay.gma4j.net.codec.encryption.client.IClientKnownCertificateKeeper;

public class NoOpCertificateKeeper implements IClientKnownCertificateKeeper {
    @Override
    public byte[] getKnownCertificateHashForUri(String uri) {
        return new byte[0];
    }

    @Override
    public boolean hasCertificateChangedForUri(String uri, byte[] serverProvidedCertificate) {
        return false;
    }
}
