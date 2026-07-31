package io.lolyay.gma4j.net.codec.encryption;

import io.lolyay.gma4j.net.codec.encryption.utils.DHUtil;
import lombok.Getter;

import java.security.SecureRandom;
import java.util.List;

public abstract class AbstractEncryptionState {
    protected final SecureRandom secureRandom = new SecureRandom();

    protected final byte[] secretKey = new byte[32];
    protected DHUtil.DhState dhState;

    @Getter
    protected ProtocolEncryptionState protocolEncryptionState = ProtocolEncryptionState.INIT;

    public List<EncryptionMode> supportedModes() {
        return List.of(EncryptionMode.GM_AES_V1);
    }
}
