package io.lolyay.gma4j.net.delivery;

import io.lolyay.gma4j.net.codec.CodecRegistry;

import java.util.Objects;

public final class DeliveryPackets {
    private DeliveryPackets() {
    }

    public static void register(CodecRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (registry.getConfig() != null) {
            throw new IllegalStateException("delivery packets must be registered before codec warmup");
        }
        registry.addCodec(DeliveryTransferPacket.TYPE);
        registry.addCodec(DeliveryAckPacket.TYPE);
    }
}
