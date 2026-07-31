package io.lolyay.gma4j.net.codec.encryption.client;

import io.lolyay.gma4j.net.codec.auth.GmaAuthType;
import io.lolyay.gma4j.net.codec.encryption.AbstractEncryptionState;
import io.lolyay.gma4j.net.codec.encryption.EncryptionMode;
import io.lolyay.gma4j.net.codec.encryption.PacketCryptor;
import io.lolyay.gma4j.net.codec.encryption.ProtocolEncryptionState;
import io.lolyay.gma4j.net.codec.encryption.utils.DHUtil;
import io.lolyay.gma4j.net.shared.SharedSecretDeriver;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
public class ClientEncryptionState extends AbstractEncryptionState {
    private final byte[] clientNonce = new byte[32];
    private final IClientKnownCertificateKeeper certificateKeeper;
    private final MessageDigest codecEncryptionStateHash;
    private String uri;

    @Getter
    private byte[] stateHash;


    @SneakyThrows
    public ClientEncryptionState(IClientKnownCertificateKeeper certificateKeeper) {
        this.certificateKeeper = certificateKeeper;
        codecEncryptionStateHash = MessageDigest.getInstance("SHA-256");
    }

    public ClientEncryptionStateInfo initConnection(String uri) {
        secureRandom.nextBytes(clientNonce); // reset nonce
        this.uri = uri;
        stateHash = new byte[32];
        dhState = DHUtil.createDhState(); // reset dhstate
        Arrays.fill(secretKey, (byte) 0);
        codecEncryptionStateHash.reset();
        protocolEncryptionState = ProtocolEncryptionState.WAITING_ON_SERVER;
        return new ClientEncryptionStateInfo(clientNonce, dhState.publicKey(), certificateKeeper.getKnownCertificateHashForUri(uri));
    }

    public boolean parseServerResponse(EncryptionMode selectedEncryptionMode,
                                       byte[] dhParams,
                                       byte[] cert,
                                       byte[] serverNonce,

                                       // Auth
                                       GmaAuthType selectedAuthType,

                                       byte[] signature,
                                       Supplier<List<GmaAuthType>> clientSupportedAuthTypesRetriever) {
        if (protocolEncryptionState != ProtocolEncryptionState.WAITING_ON_SERVER) {
            throw new IllegalStateException("ProtocolEncryptionState must be WAITING_ON_SERVER, but was " + protocolEncryptionState);
        }

        if (!this.supportedModes().contains(selectedEncryptionMode)) {
            log.error("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
            log.error("@   WARNING: SERVER SELECTED AN UNOFFERED ENCRYPTION MODE  @");
            log.error("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
            log.error("Possible DOWNGRADE ATTACK: server chose enc mode '{}', not in client-offered {}. Handshake aborted.", selectedEncryptionMode, this.supportedModes());
            throw new IllegalStateException("Server selected an encryption mode not offered by the client: " + selectedEncryptionMode);
        }

        List<GmaAuthType> clientSupportedAuthTypes = clientSupportedAuthTypesRetriever.get();
        if (!clientSupportedAuthTypes.contains(selectedAuthType)) {
            log.error("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
            log.error("@      WARNING: SERVER SELECTED AN UNOFFERED AUTH TYPE     @");
            log.error("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
            log.error("Possible DOWNGRADE ATTACK: server chose auth type '{}', not in client-offered {}. Handshake aborted.", selectedAuthType, clientSupportedAuthTypes);
            throw new IllegalStateException("Server selected an auth type not offered by the client: " + selectedAuthType);
        }


        PublicKey pubServerKey;
        try {
            pubServerKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(cert));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Error Importing Server Certificate", e);
        }

        if(certificateKeeper.hasCertificateChangedForUri(uri, cert)) {
            log.error("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
            log.error("@     WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!    @");
            log.error("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
            log.error("Server '{}' presented a different ECDSA host key than the pinned one; pinned key rejected.", uri);
            log.error("Possible MITM (DNS/ARP spoofing, path compromise) or a legitimate rotation (reinstall, key regen, IP reassignment).");
            log.error("Verify the new fingerprint over a trusted out-of-band channel before trusting this host.");
            throw new IllegalStateException("Remote Host Identification changed");
        }

        codecEncryptionStateHash.update(clientNonce); // own nonce
        codecEncryptionStateHash.update(serverNonce); // remote nonce
        codecEncryptionStateHash.update(dhState.publicKey()); // own dh
        codecEncryptionStateHash.update(dhParams); // remote dh
        codecEncryptionStateHash.update(cert); // server cert
        codecEncryptionStateHash.update((byte) selectedEncryptionMode.ordinal()); // enc mode (ss)
        codecEncryptionStateHash.update((byte) selectedAuthType.ordinal()); // auth type (ss)
        codecEncryptionStateHash.update(uri.getBytes(StandardCharsets.UTF_8)); // uri
        byte[] expectedHash = codecEncryptionStateHash.digest();
        // has to be signed by cert + signature
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(pubServerKey);
            verifier.update(expectedHash);
            if (!verifier.verify(signature)) {
                throw new IllegalStateException("Signature verification failed");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to verify signature", e);
        }

        byte[] dhSecret = DHUtil.getSharedSecret(dhState, dhParams);


        SharedSecretDeriver.derive(
                dhSecret, expectedHash, serverNonce, clientNonce, selectedAuthType, this.supportedModes(), uri,
                this.secretKey
        );

        protocolEncryptionState = ProtocolEncryptionState.ENCRYPTED;
        stateHash = expectedHash;
        return true;
    }

    public PacketCryptor createPacketCryptor() {
        if (protocolEncryptionState != ProtocolEncryptionState.ENCRYPTED) {
            throw new IllegalStateException("ProtocolEncryptionState must be ENCRYPTED, but was " + protocolEncryptionState);
        }
        return new PacketCryptor(secretKey, false);
    }


    public record ClientEncryptionStateInfo(
            byte[] clientNonce,
            byte[] dhParams,
            byte[] knownServerCertHash
    ) {};
}
