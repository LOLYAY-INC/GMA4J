package io.lolyay.gma4j.net.delivery;

import java.util.Objects;
import java.util.UUID;

record StoredTransfer(long sequence, UUID transferId, byte[] payload, byte[] digest) {
    StoredTransfer {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence cannot be negative");
        }
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(digest, "digest");
        payload = payload.clone();
        digest = digest.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public byte[] digest() {
        return digest.clone();
    }
}
