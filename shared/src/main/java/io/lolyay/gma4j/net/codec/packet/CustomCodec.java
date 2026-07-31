package io.lolyay.gma4j.net.codec.packet;

import io.lolyay.gma4j.net.shared.CodecHasher;
import io.lolyay.gma4j.net.shared.CodecType;
import lombok.SneakyThrows;

import java.util.function.BiFunction;
import java.util.function.Function;

public record CustomCodec<T extends GMAPacket<T>>(Class<T> clazz,
                                                  Function<T, byte[]> serializer,
                                                  Function<byte[],T> deserializer) implements ICodec<T> {
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
        return CodecHasher.fingerprint(clazz);
    }

    @Override
    public CodecType getCodecType() {
        return CodecType.BINARY_CUSTOM;
    }
}
