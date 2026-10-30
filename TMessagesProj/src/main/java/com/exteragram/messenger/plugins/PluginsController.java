package com.exteragram.messenger.plugins;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.plugins.ElyxPlugin;
import org.telegram.messenger.plugins.PluginInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

// exteraGram's PluginsController, as seen by plugins that reach into it (ZwyLib). It mirrors
// MyTelegram's org.telegram.messenger.plugins.PluginsController, which keeps it in sync.
public class PluginsController {

    private static final PluginsController instance = new PluginsController();
    private static final ConcurrentHashMap<String, PythonPluginsEngine> engines = new ConcurrentHashMap<>();
    private static final PythonPluginsEngine python = new PythonPluginsEngine();

    static {
        engines.put(PluginsConstants.PYTHON, python);
    }

    public final ConcurrentHashMap<String, Plugin> plugins = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HashSet<XC_MethodHook.Unhook>> xposedHooks = new ConcurrentHashMap<>();

    public static PluginsController getInstance() {
        return instance;
    }

    public static ConcurrentHashMap<String, PythonPluginsEngine> getEngines() {
        return engines;
    }

    public static PythonPluginsEngine getPythonEngine() {
        return python;
    }

    public static boolean isPlugin(MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocument() == null) {
            return false;
        }
        String name = messageObject.getDocumentName();
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(org.telegram.messenger.plugins.PluginsController.PLUGIN_EXT) || n.endsWith(".py") || ElyxPlugin.isElyxName(n);
    }

    // a plugin asks to rebuild its open settings screen
    public void loadPluginSettings(String id) {
        org.telegram.messenger.plugins.PluginsController.getInstance().reloadSettings(id);
    }

    public void notifyPluginsChanged() {
        org.telegram.messenger.plugins.PluginsController.getInstance().notifyChangedAsync();
    }

    public void setPluginEnabled(String id, boolean enabled) {
        org.telegram.messenger.plugins.PluginsController.getInstance().setEnabled(id, enabled);
    }

    public void addXposedHook(String id, XC_MethodHook.Unhook unhook) {
        if (id == null || unhook == null) return;
        HashSet<XC_MethodHook.Unhook> set = xposedHooks.get(id);
        if (set == null) {
            xposedHooks.put(id, set = new HashSet<>());
        }
        synchronized (set) {
            set.add(unhook);
        }
    }

    public void removeXposedHook(String id, XC_MethodHook.Unhook unhook) {
        if (unhook == null) return;
        unhook.unhook();
        HashSet<XC_MethodHook.Unhook> set = id == null ? null : xposedHooks.get(id);
        if (set != null) {
            synchronized (set) {
                set.remove(unhook);
            }
        }
    }

    // ---- called by MyTelegram's PluginsController ----

    public void removeAllXposedHooks(String id) {
        HashSet<XC_MethodHook.Unhook> set = xposedHooks.remove(id);
        if (set == null) return;
        ArrayList<XC_MethodHook.Unhook> copy;
        synchronized (set) {
            copy = new ArrayList<>(set);
        }
        for (XC_MethodHook.Unhook u : copy) {
            try {
                u.unhook();
            } catch (Throwable ignore) {
            }
        }
    }

    public void sync(PluginInfo info) {
        Plugin p = plugins.get(info.id);
        if (p == null) {
            plugins.put(info.id, p = new Plugin(info.id));
        }
        p.name = info.name;
        p.description = info.description;
        p.author = info.author;
        p.version = info.version;
        p.icon = info.icon;
        p.enabled = info.enabled;
        p.error = info.error != null ? new RuntimeException(info.error) : null;
    }

    public void remove(String id) {
        plugins.remove(id);
    }
}
