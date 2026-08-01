package io.lolyay.gma4j.net.codec.packet;

import lombok.Getter;
import lombok.Setter;

public class PacketType<T extends GMAPacket<T>> {

    @Setter
    private int numericId;
    @Getter
    @Setter
    private boolean system = false;
    private final ImplCodec<T> codec;
    @Getter
    private final int userSetId;

    public PacketType(int numericId, ImplCodec<T> codec) {
        this.numericId = numericId;
        this.codec = codec;
        this.userSetId = numericId;
    }

    public int numericId() {
        return numericId;
    }

    public ImplCodec<T> codec() {
        return codec;
    }
}
