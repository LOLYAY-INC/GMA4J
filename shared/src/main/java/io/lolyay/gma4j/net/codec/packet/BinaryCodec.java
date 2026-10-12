package io.lolyay.gma4j.net.codec.packet;

import java.util.function.Function;


public final class BinaryCodec<T> {
    private final Function<T, byte[]> serializer;
    private final Function<byte[],T> deserializer;

    public BinaryCodec(Function<T, byte[]> serializer, Function<byte[], T> deserializer) {
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    public byte[] serialize(T packet) {
        return serializer.apply(packet);
    }

    public T deserialize(byte[] data) {
        return deserializer.apply(data);
    }
}
