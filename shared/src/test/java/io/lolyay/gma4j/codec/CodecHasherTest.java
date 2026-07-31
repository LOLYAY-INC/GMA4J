package io.lolyay.gma4j.codec;

import io.lolyay.gma4j.codec.fixtures.BinaryPacket;
import io.lolyay.gma4j.codec.fixtures.BlobPacket;
import io.lolyay.gma4j.codec.fixtures.ComplexPacket;
import io.lolyay.gma4j.net.shared.CodecHasher;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CodecHasherTest {

    @Test
    void sameClassSameFingerprint() throws Exception {
        assertArrayEquals(
                CodecHasher.fingerprint(BinaryPacket.class),
                CodecHasher.fingerprint(BinaryPacket.class));
    }

    @Test
    void differentClassesDifferentFingerprint() throws Exception {
        byte[] a = CodecHasher.fingerprint(BinaryPacket.class);
        byte[] b = CodecHasher.fingerprint(BlobPacket.class);
        byte[] c = CodecHasher.fingerprint(ComplexPacket.class);
        assertFalse(Arrays.equals(a, b));
        assertFalse(Arrays.equals(a, c));
        assertFalse(Arrays.equals(b, c));
    }
}
