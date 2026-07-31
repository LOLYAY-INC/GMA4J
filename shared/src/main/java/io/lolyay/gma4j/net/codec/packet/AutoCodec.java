package io.lolyay.gma4j.net.codec.packet;

import io.lolyay.gma4j.net.shared.CodecHasher;
import io.lolyay.gma4j.net.shared.CodecType;
import io.lolyay.gma4j.net.shared.SharedConfig;
import io.lolyay.gma4j.net.shared.excp.NoCodecException;
import lombok.SneakyThrows;

import java.nio.charset.StandardCharsets;

import static io.lolyay.gma4j.net.shared.GsonUtil.GSON;

public final class AutoCodec<T extends GMAPacket<T>> implements ICodec<T> {
    private final Class<T> clazz;

    public AutoCodec(Class<T> clazz) {
        this.clazz = clazz;
    }


    @Override
    public byte[] serialize(T packet) {
        return switch (SharedConfig.DEFAULT_CODEC_TYPE) {
            case BINARY_CUSTOM -> throw new NoCodecException("No codec for packet " + clazz.getSimpleName());
            case GMDT -> throw new UnsupportedOperationException("GMDT codec not supported yet");
            case JSON_GSON -> GSON.toJson(packet).getBytes(StandardCharsets.UTF_8);
        };
    }

    @Override
    public T deserialize(byte[] data, CodecType codecType) {
        return switch (codecType) {
            case BINARY_CUSTOM -> throw new NoCodecException("No codec for packet " + getClass().getSimpleName());
            case GMDT -> throw new UnsupportedOperationException("GMDT codec not supported yet");
            case JSON_GSON -> jsonD(data);
        };
    }

    private T jsonD(byte[] data) {
        return GSON.fromJson(new String(data, StandardCharsets.UTF_8), clazz);
    }

    @SneakyThrows
    @Override
    public byte[] hash() {
        return CodecHasher.fingerprint(clazz);
    }
}
