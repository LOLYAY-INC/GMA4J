package io.lolyay.gma4j.net.codec.packet;

import io.lolyay.gma4j.net.shared.CodecHasher;
import io.lolyay.gma4j.net.shared.CodecType;
import io.lolyay.gma4j.net.util.PacketByteBuf;
import lombok.SneakyThrows;

import java.util.function.BiConsumer;
import java.util.function.Function;


public final class CustomCodec<T extends GMAPacket<T>> extends ImplCodec<T> implements ICodec<T> {
    private final BiConsumer<PacketByteBuf, T> serializer;
    private final Function<PacketByteBuf, T> deserializer;

    public CustomCodec(Class<T> clazz, BiConsumer<PacketByteBuf, T> serializer,
                       Function<PacketByteBuf, T> deserializer) {
        super(clazz);
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    @Override
    public byte[] serialize(T packet) {
        PacketByteBuf buf = PacketByteBuf.allocate();
        try {
            serializer.accept(buf, packet);
            return buf.toByteArray();
        } finally {
            buf.release();
        }
    }

    @Override
    public T deserialize(byte[] data, CodecType codecType) {
        if (codecType != CodecType.BINARY_CUSTOM) {
            throw new IllegalArgumentException("Invalid codec type for custom codec: " + codecType);
        }
        PacketByteBuf buf = PacketByteBuf.wrap(data);
        try {
            return deserializer.apply(buf);
        } finally {
            buf.release();
        }
    }

    @Override
    @SneakyThrows
    public byte[] hash() {
        return CodecHasher.fingerprint(getClazz());
    }

    @Override
    public CodecType getCodecType() {
        return CodecType.BINARY_CUSTOM;
    }
}
