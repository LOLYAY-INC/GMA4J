package io.lolyay.gma4j.net.codec.encryption.client;


public interface IClientKnownCertificateKeeper {
    /// Gets the known certificate for the given uri. (Storage implementation is up to the client implementation)
    /// @param uri The Server URI to get the certificate for.
    /// @return The known certificate for the given uri, or null if not found.

    byte[] getKnownCertificateHashForUri(String uri);

    /// Checks if the certificate for the given uri has changed.
    /// @param uri The Server URI to check.
    /// @param serverProvidedCertificate The certificate provided by the server.
    /// @return True if the certificate has changed, false otherwise.
    ///
    /// @implNote To comply with the standard, a form of TOFU is recommended for any client implementation.
    boolean hasCertificateChangedForUri(String uri, byte[] serverProvidedCertificate);
}
