package com.exteragram.messenger.plugins;

public final class PluginsConstants {
    public static final String PYTHON = "python";

    public static final class Xposed {
        public static final String BEFORE_HOOKED_METHOD = "before_hooked_method";
        public static final String AFTER_HOOKED_METHOD = "after_hooked_method";
        public static final String REPLACE_HOOKED_METHOD = "replace_hooked_method";
        public static final String HOOK_FILTERS = "hook_filters";
    }

    private PluginsConstants() {
    }
}
