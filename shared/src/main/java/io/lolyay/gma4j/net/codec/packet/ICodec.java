package io.lolyay.gma4j.net.codec.packet;

import io.lolyay.gma4j.net.shared.CodecType;
import io.lolyay.gma4j.net.shared.SharedConfig;

public interface ICodec<T extends GMAPacket<T>> {
    byte[] serialize(T packet);
    T deserialize(byte[] data, CodecType codecType);
    byte[] hash();

    default CodecType getCodecType() {return SharedConfig.DEFAULT_CODEC_TYPE;}
}
