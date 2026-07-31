package io.lolyay.gma4j.net.codec.packet;

import lombok.Getter;
import lombok.Setter;

public class PacketType<T extends GMAPacket<T>> {

    @Setter
    private int numericId;
    @Getter
    @Setter
    private boolean system = false;
    private final ICodec<T> codec;

    public PacketType(int numericId, ICodec<T> codec) {
        this.numericId = numericId;
        this.codec = codec;
    }

    public int numericId() {
        return numericId;
    }

    public ICodec<T> codec() {
        return codec;
    }
}
