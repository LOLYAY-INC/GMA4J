package io.lolyay.gma4j.net.codec.packet;

import io.lolyay.gma4j.net.util.PacketByteBuf;

import java.util.function.BiConsumer;
import java.util.function.Function;


public final class BinaryCodec<T> {
    private final BiConsumer<PacketByteBuf, T> serializer;
    private final Function<PacketByteBuf, T> deserializer;

    public BinaryCodec(BiConsumer<PacketByteBuf, T> serializer, Function<PacketByteBuf, T> deserializer) {
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    public byte[] serialize(T value) {
        PacketByteBuf buf = PacketByteBuf.allocate();
        try {
            serializer.accept(buf, value);
            return buf.toByteArray();
        } finally {
            buf.release();
        }
    }

    public T deserialize(byte[] data) {
        PacketByteBuf buf = PacketByteBuf.wrap(data);
        try {
            return deserializer.apply(buf);
        } finally {
            buf.release();
        }
    }
}
