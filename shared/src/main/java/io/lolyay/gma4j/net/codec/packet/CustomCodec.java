package io.lolyay.gma4j.net.codec.packet;

import io.lolyay.gma4j.net.shared.CodecHasher;
import io.lolyay.gma4j.net.shared.CodecType;
import lombok.SneakyThrows;

import java.util.function.Function;


public final class CustomCodec<T extends GMAPacket<T>> extends ImplCodec<T> implements ICodec<T> {
    private final Function<T, byte[]> serializer;
    private final Function<byte[],T> deserializer;

    public CustomCodec(Class<T> clazz, Function<T, byte[]> serializer, Function<byte[], T> deserializer) {
        super(clazz);
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    @Override
    public byte[] serialize(T packet) {
        return serializer.apply(packet);
    }

    @Override
    public T deserialize(byte[] data, CodecType codecType) {
        if (codecType != CodecType.BINARY_CUSTOM) {
            throw new IllegalArgumentException("Invalid codec type for custom codec: " + codecType);
        }
        return deserializer.apply(data);
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
