package io.lolyay.gma4j.net.codec.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public interface IGmaAuth {
    default byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    byte[] GMA_AUTH_CONTEXT = "GMA-AUTH-v1".getBytes(StandardCharsets.UTF_8);

    GmaAuthType authType();

}
