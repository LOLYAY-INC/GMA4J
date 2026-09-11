package io.lolyay.gma4j.codec.fixtures;

import io.lolyay.gma4j.net.codec.CodecRegistry;
import lombok.experimental.UtilityClass;

/**
 * One registration order for every pipeline test, ids stay stable no matter which class warms up first
 */
@UtilityClass
public class TestCodecs {

    public synchronized void registerAll() {
        CodecRegistry registry = CodecRegistry.getInstance();
        registry.addCodec(ComplexPacket.TYPE);
        registry.addCodec(LargeJsonPacket.TYPE);
        registry.addCodec(BinaryPacket.TYPE);
        registry.addCodec(TinyPacket.TYPE);
        registry.addCodec(GatedPacket.TYPE);
        registry.addCodec(IncompressiblePacket.TYPE);
        registry.warmup();
    }
}
