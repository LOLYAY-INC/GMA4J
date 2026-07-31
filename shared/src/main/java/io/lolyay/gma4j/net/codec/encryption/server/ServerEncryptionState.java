package io.lolyay.gma4j.net.codec.encryption.server;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.encryption.AbstractEncryptionState;
import io.lolyay.gma4j.net.codec.encryption.EncryptionMode;
import io.lolyay.gma4j.net.codec.encryption.PacketCryptor;
import io.lolyay.gma4j.net.codec.encryption.ProtocolEncryptionState;
import io.lolyay.gma4j.net.codec.encryption.utils.DHUtil;
import io.lolyay.gma4j.net.shared.SharedSecretDeriver;
import lombok.Getter;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

public class ServerEncryptionState extends AbstractEncryptionState {
    private final byte[] serverNonce = new byte[32];
    private final IServerCertificateProvider certificateProvider;
    private final Supplier<List<GmaAuthType>> serverSupportedAuthTypesRetriever;
    private final MessageDigest codecEncryptionStateHash;
    private String uri;

    @Getter
    private byte[] stateHash;

    @SneakyThrows
    public ServerEncryptionState(IServerCertificateProvider certificateProvider,
                                 Supplier<List<GmaAuthType>> serverSupportedAuthTypesRetriever) {
        this.certificateProvider = certificateProvider;
        this.serverSupportedAuthTypesRetriever = serverSupportedAuthTypesRetriever;
        this.codecEncryptionStateHash = MessageDigest.getInstance("SHA-256");
    }

    public ServerHelloResponse handleClientHello(String uri,
                                                 List<EncryptionMode> clientSupportedModes,
                                                 byte[] clientNonce,
                                                 byte[] clientDhParams,
                                                 List<GmaAuthType> clientSupportedAuthTypes) {
        if (protocolEncryptionState != ProtocolEncryptionState.INIT) {
            throw new IllegalStateException("ProtocolEncryptionState must be INIT, but was " + protocolEncryptionState);
        }

        this.uri = uri;
        secureRandom.nextBytes(serverNonce);
        dhState = DHUtil.createDhState();
        Arrays.fill(secretKey, (byte) 0);
        codecEncryptionStateHash.reset();

        EncryptionMode selectedEncryptionMode = selectEncryptionMode(clientSupportedModes);
        GmaAuthType selectedAuthType = selectAuthType(clientSupportedAuthTypes);

        byte[] cert = certificateProvider.getCertificate();

        codecEncryptionStateHash.update(clientNonce); // remote nonce
        codecEncryptionStateHash.update(serverNonce); // own nonce
        codecEncryptionStateHash.update(clientDhParams); // remote dh
        codecEncryptionStateHash.update(dhState.publicKey()); // own dh
        codecEncryptionStateHash.update(cert); // server cert
        codecEncryptionStateHash.update((byte) selectedEncryptionMode.ordinal()); // enc mode (ss)
        codecEncryptionStateHash.update((byte) selectedAuthType.ordinal()); // auth type (ss)
        codecEncryptionStateHash.update(uri.getBytes(StandardCharsets.UTF_8)); // uri
        byte[] transcriptHash = codecEncryptionStateHash.digest();
        stateHash = transcriptHash;

        byte[] signature;
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(certificateProvider.getSigningKey());
            signer.update(transcriptHash);
            signature = signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign handshake transcript", e);
        }

        byte[] dhSecret = DHUtil.getSharedSecret(dhState, clientDhParams);

        SharedSecretDeriver.derive(
                dhSecret, transcriptHash, serverNonce, clientNonce, selectedAuthType, clientSupportedModes, uri,
                this.secretKey
        );
        Arrays.fill(dhSecret, (byte) 0);

        protocolEncryptionState = ProtocolEncryptionState.ENCRYPTED;
        return new ServerHelloResponse(selectedEncryptionMode, dhState.publicKey(), cert, serverNonce, selectedAuthType, signature);
    }

    public PacketCryptor createPacketCryptor() {
        if (protocolEncryptionState != ProtocolEncryptionState.ENCRYPTED) {
            throw new IllegalStateException("ProtocolEncryptionState must be ENCRYPTED, but was " + protocolEncryptionState);
        }
        return new PacketCryptor(secretKey, true);
    }

    private EncryptionMode selectEncryptionMode(List<EncryptionMode> clientSupportedModes) {
        for (EncryptionMode mode : this.supportedModes()) {
            if (clientSupportedModes.contains(mode)) {
                return mode;
            }
        }
        throw new IllegalStateException("No mutually supported encryption mode. Client offered " + clientSupportedModes + ", server supports " + this.supportedModes());
    }

    private GmaAuthType selectAuthType(List<GmaAuthType> clientSupportedAuthTypes) {
        List<GmaAuthType> serverSupported = serverSupportedAuthTypesRetriever.get();
        return clientSupportedAuthTypes.stream()
                .filter(serverSupported::contains)
                .max(Comparator.comparingInt(GmaAuthType::getPriority))
                .orElseThrow(() -> new IllegalStateException("No mutually supported auth type. Client offered " + clientSupportedAuthTypes + ", server supports " + serverSupported));
    }

    public record ServerHelloResponse(
            EncryptionMode selectedEncryptionMode,
            byte[] dhParams,
            byte[] cert,
            byte[] serverNonce,
            GmaAuthType selectedAuthType,
            byte[] signature
    ) {}
}
