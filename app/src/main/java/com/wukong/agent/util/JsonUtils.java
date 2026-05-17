package com.wukong.agent.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class JsonUtils {

    private static final Gson GSON = new GsonBuilder()
            .create();

    private static final Gson GSON_PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static String toPrettyJson(Object obj) {
        return GSON_PRETTY.toJson(obj);
    }

    public static Gson getGson() {
        return GSON;
    }
}
