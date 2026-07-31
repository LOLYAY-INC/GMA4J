package io.lolyay.gma4j.net.codec.auth.client;

import lombok.NoArgsConstructor;

import java.security.interfaces.ECPrivateKey;

@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class ClientAuth {

    /**
     * @deprecated use {@link ClientAuth#hmac(String)}
     */
    @Deprecated
    public static GmaAuthClient apiKey(String apiKey) {
        return GmaApiKeyAuth.of(apiKey);
    }


    public static GmaAuthClient hmac(String secret) {
        return GmaHMACKeyAuth.of(secret);
    }

    public static GmaAuthClient hmac(byte[] secret) {
        return GmaHMACKeyAuth.of(secret);
    }


    /**
     * Creates a new {@link GmaECCKeyAuth} instance from a PEM encoded ECDSA private key.
     *
     * <p>This method supports both PEM encoded keys with headers and those without headers.
     *
     * @param privateKey the PEM encoded private key
     * @return a new {@link GmaECCKeyAuth} instance
     */
    public static GmaAuthClient ecc(String privateKey) {
        return GmaECCKeyAuth.of(privateKey);
    }

    public static GmaAuthClient ecc(ECPrivateKey privateKey) {
        return GmaECCKeyAuth.of(privateKey);
    }


    public static GmaAuthClient none() {
        return new GmaNoAuth();
    }

}
