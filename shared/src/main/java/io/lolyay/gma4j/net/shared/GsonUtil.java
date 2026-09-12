package io.lolyay.gma4j.net.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public class GsonUtil {
    public static final Gson GSON = new GsonBuilder().create();
    public static final Gson NULL_GSON = new GsonBuilder().serializeNulls().create();

    /**
     * Parses untrusted JSON straight from the wire bytes: strict grammar,
     * strict UTF-8, hard nesting cap, exactly one non null value.
     */
    public static <T> T fromJsonBounded(byte[] json, Class<T> clazz) {
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (JsonReader reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(json), utf8))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(SharedConfig.MAX_JSON_NESTING_DEPTH);
            T value = GSON.fromJson(reader, TypeToken.get(clazz));
            if (value == null) {
                throw new JsonSyntaxException("JSON null is not a " + clazz.getSimpleName());
            }
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonSyntaxException("Trailing data after " + clazz.getSimpleName());
            }
            return value;
        } catch (IOException e) {
            throw new JsonIOException(e);
        }
    }
}
