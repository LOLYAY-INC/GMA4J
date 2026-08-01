package io.lolyay.gma4j.net.codec.packet;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public abstract class ImplCodec<T extends GMAPacket<T>> implements ICodec<T> {
    private final Class<T> clazz;
}
