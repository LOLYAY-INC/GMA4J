package io.lolyay.gma4j.net.delivery;

import java.util.Objects;
import java.util.UUID;

public record InboxItem(UUID transferId, byte[] payload) {
    public InboxItem {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(payload, "payload");
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
