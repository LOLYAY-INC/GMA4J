package io.lolyay.gma4j.net.codec.packet;

import io.lolyay.gma4j.net.util.PacketByteBuf;

import java.util.function.BiConsumer;
import java.util.function.Function;


public record BinaryCodec<T>(
        BiConsumer<PacketByteBuf, T> serialize,
        Function<PacketByteBuf, T> deserialize
) {
}
