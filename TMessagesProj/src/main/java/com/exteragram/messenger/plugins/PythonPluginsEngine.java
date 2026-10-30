package com.exteragram.messenger.plugins;

import org.telegram.messenger.Utilities;
import org.telegram.messenger.plugins.ElyxPlugin;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginsController;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

// exteraGram's Python engine, as seen by plugins that reach into it (ZwyLib). Calls go to
// MyTelegram's PluginsController. unloadPlugin/setPluginEnabled are also called on every
// unload/toggle, so plugins hooking them get notified the way they do in exteraGram.
public class PythonPluginsEngine {

    // plugin id -> Python plugin instance, kept by mytg_host
    public final ConcurrentHashMap<String, Object> pluginInstances = new ConcurrentHashMap<>();

    public boolean isPlugin(File file, Object unused) {
        if (file == null) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(PluginsController.PLUGIN_EXT) || name.endsWith(".py") || ElyxPlugin.isElyxName(name) || ElyxPlugin.isZip(file);
    }

    public void loadPluginFromFile(String path, Object unused, Utilities.Callback<String> done, Object unused2) {
        File file = new File(path);
        PluginsController controller = PluginsController.getInstance();
        Utilities.Callback<String> callback = error -> {
            if (done != null) done.run(error);
        };
        if (ElyxPlugin.isZip(file)) {
            controller.installElyx(file, callback);
        } else {
            try {
                controller.install(PluginsController.readFile(file), callback);
            } catch (Exception e) {
                callback.run(e.getMessage());
            }
        }
    }

    public void loadPlugin(String id, String path) {
        PluginInfo info = PluginsController.getInstance().getPlugin(id);
        if (info != null && info.enabled) {
            PluginsController.getInstance().reload(id);
        }
    }

    public void setPluginEnabled(String id, boolean enabled, Object callback) {
        PluginsController.getInstance().setEnabled(id, enabled);
    }

    // called by PluginsController right before a plugin is unloaded
    public void unloadPlugin(String id) {
    }
}
