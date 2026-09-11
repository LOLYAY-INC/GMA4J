package io.lolyay.gma4j.codec;

import com.google.gson.JsonParseException;
import io.lolyay.gma4j.codec.fixtures.NestedJsonPacket;
import io.lolyay.gma4j.net.shared.CodecType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonNestingTest {

    private static byte[] nestedArrays(int depth) {
        StringBuilder json = new StringBuilder("{\"payload\":");
        json.append("[".repeat(depth)).append("]".repeat(depth)).append("}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void deepNestingIsRejectedNotStackOverflow() {
        byte[] hostile = nestedArrays(100_000);
        assertThrows(JsonParseException.class,
                () -> NestedJsonPacket.TYPE.codec().deserialize(hostile, CodecType.JSON_GSON));
    }

    @Test
    void shallowNestingStillParses() {
        NestedJsonPacket packet = NestedJsonPacket.TYPE.codec()
                .deserialize(nestedArrays(5), CodecType.JSON_GSON);
        assertEquals(List.of(List.of(List.of(List.of(List.of())))), packet.payload());
    }
}
