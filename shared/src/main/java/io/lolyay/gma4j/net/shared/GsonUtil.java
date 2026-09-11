package io.lolyay.gma4j.net.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;

public class GsonUtil {
    public static final Gson GSON = new GsonBuilder().create();
    public static final Gson NULL_GSON = new GsonBuilder().serializeNulls().create();

    /**
     * Parses untrusted JSON with a hard nesting cap
     */
    public static <T> T fromJsonBounded(String json, Class<T> clazz) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setNestingLimit(SharedConfig.MAX_JSON_NESTING_DEPTH);
            return GSON.fromJson(reader, TypeToken.get(clazz));
        } catch (IOException e) {
            throw new JsonIOException(e);
        }
    }
}
