package io.lolyay.gma4j.codec;

import com.google.gson.JsonSyntaxException;
import io.lolyay.gma4j.codec.fixtures.ComplexPacket;
import io.lolyay.gma4j.codec.fixtures.LargeJsonPacket;
import io.lolyay.gma4j.net.shared.CodecType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCodecTest {

    private static ComplexPacket sample() {
        Map<String, ComplexPacket.Nested> children = new HashMap<>();
        children.put("leaf", new ComplexPacket.Nested("leafKey", List.of(1.5, 2.5), Map.of()));
        List<ComplexPacket.Nested> nested = List.of(
                new ComplexPacket.Nested("root", List.of(0.1, 0.2, 0.3), children));
        return new ComplexPacket(
                "gma", 7, Long.MAX_VALUE, 3.14159, true,
                List.of("a", "b", "c"), Map.of("x", 1, "y", 2),
                nested, ComplexPacket.Suit.SPADES, List.of(10, 20, 30), "hello");
    }

    private static ComplexPacket roundTrip(ComplexPacket packet) {
        byte[] bytes = ComplexPacket.TYPE.codec().serialize(packet);
        return ComplexPacket.TYPE.codec().deserialize(bytes, CodecType.JSON_GSON);
    }

    @Test
    void complexRoundTrip() {
        ComplexPacket packet = sample();
        assertEquals(packet, roundTrip(packet));
    }

    @Test
    void unicodeAndControlChars() {
        ComplexPacket packet = new ComplexPacket(
                "na\"me\\ with\ttabs", 0, 0L, 0.0, false,
                List.of("emoji 😀🚀", "中文字符", "quote\"backslash\\", "new\nline"),
                Map.of("weird key é", 42), List.of(), ComplexPacket.Suit.HEARTS,
                List.of(), "mixed 日本語 😀 end");
        assertEquals(packet, roundTrip(packet));
    }

    @Test
    void deeplyNested() {
        ComplexPacket.Nested current = new ComplexPacket.Nested("l0", List.of(), Map.of());
        for (int i = 0; i < 40; i++) {
            Map<String, ComplexPacket.Nested> child = new HashMap<>();
            child.put("child", current);
            current = new ComplexPacket.Nested("level-" + i, List.of((double) i), child);
        }
        ComplexPacket packet = new ComplexPacket(
                "deep", 1, 1L, 1.0, true, List.of(), Map.of(),
                List.of(current), ComplexPacket.Suit.CLUBS, List.of(), "");
        assertEquals(packet, roundTrip(packet));
    }

    @Test
    void largeJsonRoundTrip() {
        List<String> items = new ArrayList<>();
        Map<String, Long> index = new HashMap<>();
        for (int i = 0; i < 25_000; i++) {
            items.add("item-" + i + "-payload-payload-payload");
            index.put("k" + i, (long) i * 1_000_000L);
        }
        LargeJsonPacket packet = new LargeJsonPacket(items, index);
        byte[] bytes = LargeJsonPacket.TYPE.codec().serialize(packet);
        assertTrue(bytes.length > 500_000, "expected a large payload, was " + bytes.length);
        LargeJsonPacket back = LargeJsonPacket.TYPE.codec().deserialize(bytes, CodecType.JSON_GSON);
        assertEquals(packet, back);
        assertEquals(25_000, back.items().size());
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(JsonSyntaxException.class, () -> ComplexPacket.TYPE.codec()
                .deserialize("{ \"name\": ".getBytes(StandardCharsets.UTF_8), CodecType.JSON_GSON));
    }

    @Test
    void wrongShapeJsonThrows() {
        assertThrows(JsonSyntaxException.class, () -> ComplexPacket.TYPE.codec()
                .deserialize("[1,2,3]".getBytes(StandardCharsets.UTF_8), CodecType.JSON_GSON));
    }

    @Test
    void truncatedJsonThrows() {
        byte[] full = ComplexPacket.TYPE.codec().serialize(sample());
        byte[] truncated = Arrays.copyOf(full, full.length / 2);
        assertThrows(JsonSyntaxException.class,
                () -> ComplexPacket.TYPE.codec().deserialize(truncated, CodecType.JSON_GSON));
    }
}
