package io.lolyay.gma4j.net.codec.packet;

import lombok.Getter;
import lombok.Setter;

import java.util.function.Predicate;

public class PacketType<T extends GMAPacket<T>> {

    @Setter
    private int numericId;
    @Getter
    @Setter
    private boolean system = false;
    private final ImplCodec<T> codec;
    @Getter
    private final int userSetId;
    /** Matching key across nodes together with userSetId, null keeps hash/name matching */
    @Getter
    private final String namespace;
    private final Predicate<T> shouldCompress;

    public PacketType(int numericId, ImplCodec<T> codec) {
        this(numericId, codec, null, packet -> true);
    }

    public PacketType(int numericId, ImplCodec<T> codec, Predicate<T> shouldCompress) {
        this(numericId, codec, null, shouldCompress);
    }

    public PacketType(int numericId, ImplCodec<T> codec, String namespace) {
        this(numericId, codec, namespace, packet -> true);
    }

    public PacketType(int numericId, ImplCodec<T> codec, String namespace, Predicate<T> shouldCompress) {
        if (namespace != null && namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must be null or non-blank");
        }
        this.numericId = numericId;
        this.codec = codec;
        this.userSetId = numericId;
        this.namespace = namespace;
        this.shouldCompress = shouldCompress;
    }

    public int numericId() {
        return numericId;
    }

    public ImplCodec<T> codec() {
        return codec;
    }

    /** Whether this packet may be compressed on the wire */
    public boolean shouldCompress(T packet) {
        return shouldCompress.test(packet);
    }
}
