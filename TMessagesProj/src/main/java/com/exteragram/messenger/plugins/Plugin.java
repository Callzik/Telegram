package com.exteragram.messenger.plugins;

// exteraGram's plugin model, mirrored from MyTelegram's PluginInfo for plugins that read it.
public class Plugin {

    private final String id;
    String name, description, author, version, icon;
    boolean enabled;
    Throwable error;

    public Plugin(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthor() {
        return author;
    }

    public String getVersion() {
        return version;
    }

    public String getIcon() {
        return icon;
    }

    // icon is "pack/index"
    public String getPack() {
        if (icon == null) return null;
        int slash = icon.indexOf('/');
        return slash > 0 ? icon.substring(0, slash) : null;
    }

    public int getIndex() {
        if (icon == null) return -1;
        int slash = icon.indexOf('/');
        try {
            return slash > 0 ? Integer.parseInt(icon.substring(slash + 1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String getEngine() {
        return PluginsConstants.PYTHON;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Throwable getError() {
        return error;
    }

    public boolean hasError() {
        return error != null;
    }
}
