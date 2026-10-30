package org.telegram.messenger.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// Python plugins (exteraGram-style .plugin files) run by Chaquopy.
//
// Everything Python runs on one background queue. Hot paths of the client only do a
// volatile read plus a class lookup in a small cache, and go to Python only when an
// enabled plugin subscribed to that exact request/update. Hooked work is handed to the
// plugin queue and resumed on its original queue, so a slow plugin delays only what it
// hooks and never blocks the UI. A hook that does not answer in HOOK_TIMEOUT_MS lets the
// original data through and suspends hooks until the plugin returns.
public class PluginsController {

    public static final String PLUGIN_EXT = ".plugin";
    public static final long MAX_PLUGIN_SIZE = 2 * 1024 * 1024;

    private static final long HOOK_TIMEOUT_MS = 5000;
    private static final int MAX_LOG_LINES = 300;
    private static final int CRASHES_FOR_SAFE_MODE = 3;

    // hook result codes shared with mytg_host.py
    private static final int RESULT_DEFAULT = 0;
    private static final int RESULT_CANCEL = 1;

    private static final int FLAG_PRE = 1;
    private static final int FLAG_POST = 2;
    private static final int FLAG_UPDATE = 4;
    private static final int FLAG_UPDATES = 8;

    private static volatile PluginsController instance;

    // hook registry, replaced as a whole by setHooks() from Python
    private static volatile boolean hooksActive;
    private static volatile boolean hooksSuspended;
    private static volatile boolean sendMessageHooked;
    private static volatile HookNames hookNames = new HookNames();
    private static volatile ConcurrentHashMap<Class<?>, Integer> flagsCache = new ConcurrentHashMap<>();

    private final SharedPreferences prefs;
    private final File dir;
    private final LinkedHashMap<String, PluginInfo> plugins = new LinkedHashMap<>();
    private final HashMap<String, ArrayDeque<String>> logs = new HashMap<>();
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    private DispatchQueue queue;
    private DispatchQueue watchdog;

    // touched only on the plugin queue
    private PyObject host;
    private String pythonError;

    public volatile boolean masterEnabled;
    public volatile boolean safeModeTriggered;

    public static PluginsController getInstance() {
        PluginsController local = instance;
        if (local == null) {
            synchronized (PluginsController.class) {
                local = instance;
                if (local == null) {
                    instance = local = new PluginsController();
                }
            }
        }
        return local;
    }

    private PluginsController() {
        Context context = ApplicationLoader.applicationContext;
        prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE);
        dir = new File(context.getFilesDir(), "plugins");
        masterEnabled = prefs.getBoolean("enabled", true);
        scanPlugins();
    }

    public static void init() {
        Utilities.globalQueue.postRunnable(PluginsController::initInternal);
    }

    private static void initInternal() {
        PluginsController controller = getInstance();
        boolean any = false;
        for (PluginInfo info : controller.getPlugins()) {
            any |= info.enabled;
        }
        if (!controller.masterEnabled || !any || !controller.checkSafeMode()) {
            return;
        }
        controller.getQueue().postRunnable(() -> {
            controller.loadEnabledPlugins();
            // crashes right after loading (e.g. in a fresh method hook) still count
            controller.getWatchdog().postRunnable(() -> controller.prefs.edit().putBoolean("starting", false).putInt("startCrashes", 0).apply(), 5000);
        });
    }

    // A native crash while plugins load would crash every launch, so after several
    // launches in a row that died while loading plugins, start with plugins switched off.
    private boolean checkSafeMode() {
        int crashes = prefs.getBoolean("starting", false) ? prefs.getInt("startCrashes", 0) + 1 : 0;
        if (crashes >= CRASHES_FOR_SAFE_MODE) {
            masterEnabled = false;
            safeModeTriggered = true;
            prefs.edit().putBoolean("enabled", false).putBoolean("starting", false).putInt("startCrashes", 0).commit();
            AndroidUtilities.runOnUIThread(this::notifyChanged);
            return false;
        }
        prefs.edit().putBoolean("starting", true).putInt("startCrashes", crashes).commit();
        return true;
    }

    public DispatchQueue getQueue() {
        if (queue == null) {
            synchronized (this) {
                if (queue == null) {
                    queue = new DispatchQueue("pluginsQueue");
                }
            }
        }
        return queue;
    }

    private DispatchQueue getWatchdog() {
        if (watchdog == null) {
            synchronized (this) {
                if (watchdog == null) {
                    watchdog = new DispatchQueue("pluginsWatchdog");
                }
            }
        }
        return watchdog;
    }

    // ---------------------------------------------------------------------------------
    // plugin files

    private void scanPlugins() {
        Set<String> enabled = prefs.getStringSet("enabledPlugins", Collections.emptySet());
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.getName().endsWith(PLUGIN_EXT)) {
                continue;
            }
            try {
                PluginInfo info = PluginInfo.parse(readFile(file));
                info.file = file;
                info.enabled = enabled.contains(info.id);
                synchronized (plugins) {
                    plugins.put(info.id, info);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        File[] elyx = new File(dir, ElyxPlugin.DIR).listFiles();
        if (elyx == null) {
            return;
        }
        for (File folder : elyx) {
            if (!folder.isDirectory() || folder.getName().endsWith(".tmp")) {
                continue;
            }
            try {
                PluginInfo info = ElyxPlugin.parseDir(folder);
                info.file = folder;
                info.enabled = enabled.contains(info.id);
                synchronized (plugins) {
                    plugins.put(info.id, info);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public File getArchive(PluginInfo info) {
        return new File(new File(dir, ElyxPlugin.ARCHIVES), info.id + ElyxPlugin.EXT);
    }

    // Copies a picked file into the cache, so the install dialog can look at it.
    public static File copyUriToTemp(Uri uri) throws Exception {
        File tmp = new File(ApplicationLoader.applicationContext.getCacheDir(), "plugin_import.tmp");
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                throw new IllegalArgumentException("can't open file");
            }
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > ElyxPlugin.MAX_SIZE) {
                    throw new IllegalArgumentException("plugin file is too large");
                }
                out.write(buf, 0, n);
            }
        }
        return tmp;
    }

    public ArrayList<PluginInfo> getPlugins() {
        ArrayList<PluginInfo> list;
        synchronized (plugins) {
            list = new ArrayList<>(plugins.values());
        }
        Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    public PluginInfo getPlugin(String id) {
        synchronized (plugins) {
            return plugins.get(id);
        }
    }

    private void saveEnabled() {
        HashSet<String> set = new HashSet<>();
        synchronized (plugins) {
            for (PluginInfo info : plugins.values()) {
                if (info.enabled) {
                    set.add(info.id);
                }
            }
        }
        prefs.edit().putStringSet("enabledPlugins", set).apply();
    }

    public static String readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in);
        }
    }

    public static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_PLUGIN_SIZE) {
                throw new IllegalArgumentException("plugin file is too large");
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static String readUri(Uri uri) throws Exception {
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalArgumentException("can't open file");
            }
            return readStream(in);
        }
    }

    // Writes the plugin into the plugins folder, replacing an older version, and loads it.
    // Calls back on the UI thread with null on success or an error text.
    public void install(String source, Utilities.Callback<String> done) {
        getQueue().postRunnable(() -> {
            String error = null;
            try {
                PluginInfo info = PluginInfo.parse(source);
                PluginInfo old = getPlugin(info.id);
                if (old != null && old.loaded) {
                    unloadOnQueue(old);
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("can't create " + dir);
                }
                File file = new File(dir, info.id + PLUGIN_EXT);
                File tmp = new File(dir, info.id + PLUGIN_EXT + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(source.getBytes(StandardCharsets.UTF_8));
                }
                if (old != null && old.elyx) {
                    removeFiles(old);
                }
                if (!tmp.renameTo(file)) {
                    throw new IllegalStateException("can't write " + file);
                }
                finishInstall(info, file);
                error = info.error;
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage();
            }
            final String result = error;
            AndroidUtilities.runOnUIThread(() -> {
                notifyChanged();
                if (done != null) done.run(result);
            });
        });
    }

    // Same for an .elyx archive: unpacks it to ElyxPlugins/<id>/ and keeps a copy of it.
    public void installElyx(File archive, Utilities.Callback<String> done) {
        getQueue().postRunnable(() -> {
            String error = null;
            try {
                PluginInfo info = ElyxPlugin.parseArchive(archive);
                PluginInfo old = getPlugin(info.id);
                if (old != null && old.loaded) {
                    unloadOnQueue(old);
                }
                File root = new File(dir, ElyxPlugin.DIR);
                File archives = new File(dir, ElyxPlugin.ARCHIVES);
                if (!root.exists() && !root.mkdirs() || !archives.exists() && !archives.mkdirs()) {
                    throw new IllegalStateException("can't create " + root);
                }
                File target = new File(root, info.id);
                File tmp = new File(root, info.id + ".tmp");
                ElyxPlugin.deleteRecursive(tmp);
                try {
                    ElyxPlugin.extract(archive, tmp);
                } catch (Exception e) {
                    ElyxPlugin.deleteRecursive(tmp);
                    throw e;
                }
                File keep = getArchive(info);
                if (!keep.getCanonicalPath().equals(archive.getCanonicalPath())) {
                    ElyxPlugin.copy(archive, keep);
                }
                if (old != null) {
                    if (old.elyx) {
                        ElyxPlugin.deleteRecursive(target);
                    } else if (old.file != null) {
                        old.file.delete();
                    }
                }
                ElyxPlugin.deleteRecursive(target);
                if (!tmp.renameTo(target)) {
                    throw new IllegalStateException("can't write " + target);
                }
                finishInstall(info, target);
                error = info.error;
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage();
            }
            final String result = error;
            AndroidUtilities.runOnUIThread(() -> {
                notifyChanged();
                if (done != null) done.run(result);
            });
        });
    }

    private void finishInstall(PluginInfo info, File file) {
        info.file = file;
        info.enabled = true;
        synchronized (plugins) {
            plugins.put(info.id, info);
        }
        saveEnabled();
        if (masterEnabled) {
            loadOnQueue(info);
            if (info.loaded) {
                retryDependents(info.id);
            }
        }
    }

    private void removeFiles(PluginInfo info) {
        if (info.elyx) {
            if (info.file != null) {
                ElyxPlugin.deleteRecursive(info.file);
            }
            getArchive(info).delete();
        } else if (info.file != null) {
            info.file.delete();
        }
    }

    public void delete(String id) {
        PluginInfo info = getPlugin(id);
        if (info == null) {
            return;
        }
        synchronized (plugins) {
            plugins.remove(id);
        }
        saveEnabled();
        getQueue().postRunnable(() -> {
            if (info.loaded) {
                unloadOnQueue(info);
            }
            removeFiles(info);
            settingsPrefs(id).edit().clear().apply();
            synchronized (logs) {
                logs.remove(id);
            }
            AndroidUtilities.runOnUIThread(this::notifyChanged);
        });
    }

    public void setEnabled(String id, boolean enabled) {
        PluginInfo info = getPlugin(id);
        if (info == null || info.enabled == enabled) {
            return;
        }
        info.enabled = enabled;
        if (!enabled) {
            info.error = null;
        }
        try {
            // exteraGram plugins (ZwyLib) watch toggles by hooking this method
            com.exteragram.messenger.plugins.PluginsController.getInstance().setPluginEnabled(id, enabled);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        saveEnabled();
        notifyChanged();
        if (!masterEnabled) {
            return;
        }
        getQueue().postRunnable(() -> {
            if (enabled) {
                loadOnQueue(info);
                if (info.loaded) {
                    retryDependents(info.id);
                }
            } else if (info.loaded) {
                unloadOnQueue(info);
            }
            AndroidUtilities.runOnUIThread(this::notifyChanged);
        });
    }

    public void setMasterEnabled(boolean enabled) {
        if (masterEnabled == enabled) {
            return;
        }
        masterEnabled = enabled;
        safeModeTriggered = false;
        prefs.edit().putBoolean("enabled", enabled).apply();
        notifyChanged();
        getQueue().postRunnable(() -> {
            if (enabled) {
                loadEnabledPlugins();
            } else {
                for (PluginInfo info : getPlugins()) {
                    if (info.loaded) {
                        unloadOnQueue(info);
                    }
                }
            }
            AndroidUtilities.runOnUIThread(this::notifyChanged);
        });
    }

    // ---------------------------------------------------------------------------------
    // python side, plugin queue only

    private boolean ensurePython() {
        if (host != null) {
            return true;
        }
        if (pythonError != null) {
            return false;
        }
        try {
            long start = System.currentTimeMillis();
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
            }
            host = Python.getInstance().getModule("mytg_host");
            FileLog.d("plugins: python started in " + (System.currentTimeMillis() - start) + " ms");
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            pythonError = String.valueOf(e);
            return false;
        }
    }

    private void loadEnabledPlugins() {
        ArrayList<PluginInfo> failed = new ArrayList<>();
        for (PluginInfo info : getPlugins()) {
            if (info.enabled && !info.loaded) {
                loadOnQueue(info);
                if (!info.loaded && isImportError(info.error)) {
                    failed.add(info);
                }
            }
        }
        // a plugin may import a library plugin (import zwylib) that sorts after it
        for (int pass = 0; pass < 3 && !failed.isEmpty(); pass++) {
            ArrayList<PluginInfo> again = new ArrayList<>();
            for (PluginInfo info : failed) {
                loadOnQueue(info);
                if (!info.loaded) {
                    again.add(info);
                }
            }
            if (again.size() == failed.size()) {
                break;
            }
            failed = again;
        }
        AndroidUtilities.runOnUIThread(this::notifyChanged);
    }

    private static boolean isImportError(String error) {
        return error != null && (error.contains("ModuleNotFoundError") || error.contains("ImportError") || error.contains("Cannot run without"));
    }

    // Plugins that failed to import the plugin that was just loaded get another try.
    private void retryDependents(String id) {
        for (PluginInfo other : getPlugins()) {
            if (other.enabled && !other.loaded && other.error != null && other.error.contains(id)) {
                loadOnQueue(other);
            }
        }
    }

    private void loadOnQueue(PluginInfo info) {
        if (!ensurePython()) {
            info.error = "Python: " + pythonError;
            return;
        }
        try {
            List<PyObject> result;
            if (info.elyx) {
                result = host.callAttr("load_elyx_plugin", info.id, info.file.getAbsolutePath(), info.elyxMain,
                    info.elyxMeta, info.elyxRefmap, info.elyxStrings, info.elyxAssets).asList();
            } else {
                result = host.callAttr("load_plugin", info.id, info.file.getAbsolutePath()).asList();
            }
            PyObject error = result.get(0);
            if (error != null) {
                info.error = error.toString();
                info.loaded = false;
                log(info.id, info.error);
            } else {
                info.error = null;
                info.loaded = true;
                info.hasSettings = result.get(1).toBoolean();
            }
        } catch (Throwable e) {
            FileLog.e(e);
            info.error = String.valueOf(e);
            info.loaded = false;
        }
    }

    private void unloadOnQueue(PluginInfo info) {
        info.loaded = false;
        info.hasSettings = false;
        if (host == null) {
            return;
        }
        try {
            com.exteragram.messenger.plugins.PluginsController.getPythonEngine().unloadPlugin(info.id);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            host.callAttr("unload_plugin", info.id);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        com.exteragram.messenger.plugins.PluginsController.getInstance().removeAllXposedHooks(info.id);
        PluginMenus.removeAll(info.id);
    }

    // Builds the plugin's settings (create_settings) and returns them as JSON on the UI thread.
    public void requestSettings(String id, Utilities.Callback<String> callback) {
        getQueue().postRunnable(() -> {
            String json = null;
            PluginInfo info = getPlugin(id);
            if (host != null && info != null && info.loaded) {
                try {
                    PyObject result = host.callAttr("get_settings_json", id);
                    json = result == null ? null : result.toString();
                } catch (Throwable e) {
                    FileLog.e(e);
                    log(id, String.valueOf(e));
                }
            }
            final String finalJson = json;
            AndroidUtilities.runOnUIThread(() -> callback.run(finalJson));
        });
    }

    // A settings item was clicked or changed; index is its position in the last create_settings() list.
    public void settingsEvent(String id, int index, Object value, Runnable after) {
        getQueue().postRunnable(() -> {
            if (host != null) {
                try {
                    host.callAttr("settings_event", id, index, value);
                } catch (Throwable e) {
                    FileLog.e(e);
                    log(id, String.valueOf(e));
                }
            }
            if (after != null) {
                AndroidUtilities.runOnUIThread(after);
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // listeners

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        syncCompat();
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    }

    public void notifyChangedAsync() {
        AndroidUtilities.runOnUIThread(this::notifyChanged);
    }

    // a plugin changed its settings items; open settings screens rebuild them
    public void reloadSettings(String id) {
        notifyChangedAsync();
    }

    // unload and load again (exteraGram API, used by library plugins)
    public void reload(String id) {
        PluginInfo info = getPlugin(id);
        if (info == null) {
            return;
        }
        getQueue().postRunnable(() -> {
            if (info.loaded) {
                unloadOnQueue(info);
            }
            if (info.enabled && masterEnabled) {
                loadOnQueue(info);
            }
            notifyChangedAsync();
        });
    }

    // keeps exteraGram's view of the plugin list (com.exteragram...PluginsController) in sync
    private void syncCompat() {
        com.exteragram.messenger.plugins.PluginsController compat = com.exteragram.messenger.plugins.PluginsController.getInstance();
        HashSet<String> ids = new HashSet<>();
        for (PluginInfo info : getPlugins()) {
            compat.sync(info);
            ids.add(info.id);
        }
        for (String id : new ArrayList<>(compat.plugins.keySet())) {
            if (!ids.contains(id)) {
                compat.remove(id);
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // called from Python (mytg_host.py / plugin API)

    public static void setHooks(String[] pre, String[] post, String[] update, String[] updates, boolean sendMessage) {
        HookNames names = new HookNames();
        names.add(names.pre, names.preSub, pre);
        names.add(names.post, names.postSub, post);
        names.add(names.update, names.updateSub, update);
        names.add(names.updates, names.updatesSub, updates);
        hookNames = names;
        flagsCache = new ConcurrentHashMap<>();
        sendMessageHooked = sendMessage;
        hooksActive = names.any() || sendMessage;
    }

    public static void log(String id, String text) {
        FileLog.d("plugin " + id + ": " + text);
        PluginsController controller = getInstance();
        synchronized (controller.logs) {
            ArrayDeque<String> lines = controller.logs.get(id);
            if (lines == null) {
                controller.logs.put(id, lines = new ArrayDeque<>());
            }
            String time = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
            lines.addLast(time + "  " + text);
            while (lines.size() > MAX_LOG_LINES) {
                lines.removeFirst();
            }
        }
    }

    public static void reportError(String id, String text) {
        log(id, text);
        PluginInfo info = getInstance().getPlugin(id);
        if (info != null) {
            info.error = text;
        }
    }

    public String getLog(String id) {
        synchronized (logs) {
            ArrayDeque<String> lines = logs.get(id);
            return lines == null ? "" : TextUtils.join("\n", lines);
        }
    }

    public void clearLog(String id) {
        synchronized (logs) {
            logs.remove(id);
        }
    }

    private static SharedPreferences settingsPrefs(String id) {
        return ApplicationLoader.applicationContext.getSharedPreferences("plugin_" + id, Context.MODE_PRIVATE);
    }

    public static Object getSetting(String id, String key) {
        return settingsPrefs(id).getAll().get(key);
    }

    public static Map<String, ?> getAllSettings(String id) {
        return settingsPrefs(id).getAll();
    }

    public static void putBoolean(String id, String key, boolean value) {
        settingsPrefs(id).edit().putBoolean(key, value).apply();
    }

    public static void putLong(String id, String key, long value) {
        settingsPrefs(id).edit().putLong(key, value).apply();
    }

    public static void putFloat(String id, String key, float value) {
        settingsPrefs(id).edit().putFloat(key, value).apply();
    }

    public static void putString(String id, String key, String value) {
        settingsPrefs(id).edit().putString(key, value).apply();
    }

    public static void removeSetting(String id, String key) {
        settingsPrefs(id).edit().remove(key).apply();
    }

    public static String yamlToJson(String text) {
        return MiniYaml.toJson(text);
    }

    public static String getPluginsDir() {
        return getInstance().dir.getAbsolutePath();
    }

    public static void runOnPluginQueue(Runnable runnable, long delay) {
        if (delay > 0) {
            getInstance().getQueue().postRunnable(runnable, delay);
        } else {
            getInstance().getQueue().postRunnable(runnable);
        }
    }

    // ---------------------------------------------------------------------------------
    // client hooks

    private static int flags(Class<?> cls) {
        ConcurrentHashMap<Class<?>, Integer> cache = flagsCache;
        Integer cached = cache.get(cls);
        if (cached == null) {
            cache.put(cls, cached = hookNames.flags(cls.getSimpleName()));
        }
        return cached;
    }

    private static boolean hooked(Object object, int flag) {
        return hooksActive && !hooksSuspended && object != null && (flags(object.getClass()) & flag) != 0;
    }

    public static boolean isSendMessageHooked() {
        return hooksActive && !hooksSuspended && sendMessageHooked;
    }

    // ConnectionsManager, before a request is serialized. proceed gets the request to send
    // (maybe replaced by a plugin) on the stage queue, or is not called when a plugin cancels it.
    public static boolean interceptRequest(int account, TLObject request, Utilities.Callback<TLObject> proceed) {
        if (!hooked(request, FLAG_PRE)) {
            return false;
        }
        PluginsController controller = getInstance();
        controller.dispatch(() -> {
            List<PyObject> r = controller.host.callAttr("pre_request", request.getClass().getSimpleName(), account, request).asList();
            if (r.get(0).toInt() == RESULT_CANCEL) {
                return null;
            }
            Object replaced = r.get(1) == null ? null : r.get(1).toJava(Object.class);
            return replaced instanceof TLObject ? (TLObject) replaced : request;
        }, request, result -> {
            if (result != null) proceed.run(result);
        }, Utilities.stageQueue);
        return true;
    }

    public static boolean isResponseHooked(TLObject request) {
        return hooked(request, FLAG_POST);
    }

    // ConnectionsManager, after a response was parsed. deliver runs on the stage queue.
    public static void interceptResponse(int account, TLObject request, TLObject response, TLRPC.TL_error error, Utilities.Callback2<TLObject, TLRPC.TL_error> deliver) {
        PluginsController controller = getInstance();
        final Object[] fallback = new Object[]{response, error};
        controller.dispatch(() -> {
            List<PyObject> r = controller.host.callAttr("post_request", request.getClass().getSimpleName(), account, response, error).asList();
            if (r.get(0).toInt() == RESULT_CANCEL) {
                return null;
            }
            Object resp = r.get(1) == null ? null : r.get(1).toJava(Object.class);
            Object err = r.get(2) == null ? null : r.get(2).toJava(Object.class);
            return new Object[]{resp instanceof TLObject ? resp : null, err instanceof TLRPC.TL_error ? err : null};
        }, fallback, result -> {
            if (result != null) deliver.run((TLObject) result[0], (TLRPC.TL_error) result[1]);
        }, Utilities.stageQueue);
    }

    public static boolean isUpdatesHooked(TLRPC.Updates updates) {
        if (!hooksActive || hooksSuspended || updates == null) {
            return false;
        }
        HookNames names = hookNames;
        if (names.updates.isEmpty() && names.updatesSub.isEmpty() && names.update.isEmpty() && names.updateSub.isEmpty()) {
            return false;
        }
        if ((flags(updates.getClass()) & FLAG_UPDATES) != 0) {
            return true;
        }
        if (updates.update != null && (flags(updates.update.getClass()) & FLAG_UPDATE) != 0) {
            return true;
        }
        if (updates.updates != null) {
            for (int i = 0, n = updates.updates.size(); i < n; i++) {
                TLRPC.Update u = updates.updates.get(i);
                if (u != null && (flags(u.getClass()) & FLAG_UPDATE) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    // MessagesController.processUpdates. proceed runs on the stage queue in the original order.
    public static void interceptUpdates(int account, TLRPC.Updates updates, Utilities.Callback<TLRPC.Updates> proceed) {
        PluginsController controller = getInstance();
        controller.dispatch(() -> {
            List<PyObject> r = controller.host.callAttr("on_updates", updates.getClass().getSimpleName(), account, updates).asList();
            if (r.get(0).toInt() == RESULT_CANCEL) {
                return null;
            }
            Object replaced = r.get(1) == null ? null : r.get(1).toJava(Object.class);
            return replaced instanceof TLRPC.Updates ? (TLRPC.Updates) replaced : updates;
        }, updates, result -> {
            if (result != null) proceed.run(result);
        }, Utilities.stageQueue);
    }

    // SendMessagesHelper.sendMessage. proceed runs on the UI thread.
    public static void interceptSendMessage(int account, SendMessagesHelper.SendMessageParams params, Utilities.Callback<SendMessagesHelper.SendMessageParams> proceed) {
        PluginsController controller = getInstance();
        controller.dispatch(() -> {
            List<PyObject> r = controller.host.callAttr("on_send_message", account, params).asList();
            if (r.get(0).toInt() == RESULT_CANCEL) {
                return null;
            }
            Object replaced = r.get(1) == null ? null : r.get(1).toJava(Object.class);
            return replaced instanceof SendMessagesHelper.SendMessageParams ? (SendMessagesHelper.SendMessageParams) replaced : params;
        }, params, result -> {
            if (result != null) {
                result.pluginsProcessed = true;
                proceed.run(result);
            }
        }, null);
    }

    private interface HookCall<T> {
        T call() throws Throwable;
    }

    // Runs call on the plugin queue and passes its result to cont on target (UI thread if null).
    // If the plugin is too slow, cont gets the fallback instead and hooks are suspended until it returns.
    private <T> void dispatch(HookCall<T> call, T fallback, Utilities.Callback<T> cont, DispatchQueue target) {
        final AtomicBoolean done = new AtomicBoolean();
        final Runnable onTimeout = () -> {
            if (done.compareAndSet(false, true)) {
                hooksSuspended = true;
                FileLog.e("plugins: hook timed out, hooks suspended");
                deliver(cont, fallback, target);
            }
        };
        getWatchdog().postRunnable(onTimeout, HOOK_TIMEOUT_MS);
        getQueue().postRunnable(() -> {
            if (done.get()) {
                return;
            }
            T result;
            if (host == null) {
                result = fallback;
            } else {
                try {
                    result = call.call();
                } catch (Throwable e) {
                    FileLog.e(e);
                    result = fallback;
                }
            }
            if (done.compareAndSet(false, true)) {
                getWatchdog().cancelRunnable(onTimeout);
                deliver(cont, result, target);
            } else {
                hooksSuspended = false;
            }
        });
    }

    private static <T> void deliver(Utilities.Callback<T> cont, T value, DispatchQueue target) {
        Runnable r = () -> {
            try {
                cont.run(value);
            } catch (Exception e) {
                FileLog.e(e);
            }
        };
        if (target == null) {
            AndroidUtilities.runOnUIThread(r);
        } else {
            target.postRunnable(r);
        }
    }

    private static class HookNames {
        final HashSet<String> pre = new HashSet<>(), post = new HashSet<>(), update = new HashSet<>(), updates = new HashSet<>();
        final ArrayList<String> preSub = new ArrayList<>(), postSub = new ArrayList<>(), updateSub = new ArrayList<>(), updatesSub = new ArrayList<>();

        // names starting with '~' match as substrings (add_hook(match_substring=True))
        void add(HashSet<String> exact, ArrayList<String> sub, String[] names) {
            if (names == null) return;
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                if (name.charAt(0) == '~') {
                    sub.add(name.substring(1));
                } else {
                    exact.add(name);
                }
            }
        }

        boolean any() {
            return !pre.isEmpty() || !post.isEmpty() || !update.isEmpty() || !updates.isEmpty()
                || !preSub.isEmpty() || !postSub.isEmpty() || !updateSub.isEmpty() || !updatesSub.isEmpty();
        }

        int flags(String name) {
            int f = 0;
            if (matches(pre, preSub, name)) f |= FLAG_PRE;
            if (matches(post, postSub, name)) f |= FLAG_POST;
            if (matches(update, updateSub, name)) f |= FLAG_UPDATE;
            if (matches(updates, updatesSub, name)) f |= FLAG_UPDATES;
            return f;
        }

        private static boolean matches(HashSet<String> exact, ArrayList<String> sub, String name) {
            if (exact.contains(name)) return true;
            for (int i = 0; i < sub.size(); i++) {
                if (name.contains(sub.get(i))) return true;
            }
            return false;
        }
    }
}
