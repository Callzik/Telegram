package com.exteragram.messenger.utils;

import com.google.gson.Gson;

public final class AppUtils {

    private static Gson gson;

    private AppUtils() {
    }

    public static synchronized Gson getGson() {
        if (gson == null) {
            gson = new Gson();
        }
        return gson;
    }
}
