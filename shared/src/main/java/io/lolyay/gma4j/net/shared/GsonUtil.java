package io.lolyay.gma4j.net.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class GsonUtil {
    public static final Gson GSON = new GsonBuilder().create();
    public static final Gson NULL_GSON = new GsonBuilder().serializeNulls().create();
}
