package org.telegram.messenger.plugins;

import android.app.Activity;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.lang.reflect.Field;
import java.lang.reflect.Member;

import top.canyie.pine.Pine;
import top.canyie.pine.PineConfig;
import top.canyie.pine.callback.MethodHook;

// Small Java helpers that the Python plugin API (android_utils, ui.*, hook_utils) calls.
public final class PluginBridge {

    public static final int BULLETIN_INFO = 0;
    public static final int BULLETIN_ERROR = 1;
    public static final int BULLETIN_SUCCESS = 2;

    private static boolean pineReady;

    private PluginBridge() {
    }

    public static BaseFragment getLastFragment() {
        return LaunchActivity.getSafeLastFragment();
    }

    public static Activity getActivity() {
        BaseFragment fragment = getLastFragment();
        return fragment != null ? fragment.getParentActivity() : LaunchActivity.instance;
    }

    public static void showBulletin(int type, String text) {
        AndroidUtilities.runOnUIThread(() -> {
            BaseFragment fragment = getLastFragment();
            BulletinFactory factory = fragment != null ? BulletinFactory.of(fragment) : BulletinFactory.global();
            if (factory == null) {
                return;
            }
            int icon = type == BULLETIN_ERROR ? R.raw.error : type == BULLETIN_SUCCESS ? R.raw.contact_check : R.raw.info;
            factory.createSimpleBulletin(icon, text).show();
        });
    }

    // onOk/onCancel are Python callables (or None), called on the plugin queue
    public static void showAlert(String title, String message, String okText, String cancelText, PyObject onOk, PyObject onCancel) {
        AndroidUtilities.runOnUIThread(() -> {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            if (title != null) builder.setTitle(title);
            if (message != null) builder.setMessage(message);
            builder.setPositiveButton(okText != null ? okText : LocaleController.getString(R.string.OK), (d, w) -> callLater(onOk));
            if (cancelText != null) {
                builder.setNegativeButton(cancelText, (d, w) -> callLater(onCancel));
            }
            BaseFragment fragment = getLastFragment();
            if (fragment != null) {
                fragment.showDialog(builder.create());
            } else {
                builder.show();
            }
        });
    }

    private static void callLater(PyObject callable) {
        if (callable == null) {
            return;
        }
        PluginsController.runOnPluginQueue(() -> {
            try {
                callable.call();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }, 0);
    }

    public static void copyToClipboard(String text) {
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.addToClipboard(text));
    }

    public static String getString(String key) {
        int res = ApplicationLoader.applicationContext.getResources().getIdentifier(key, "string", ApplicationLoader.applicationContext.getPackageName());
        return res != 0 ? LocaleController.getString(res) : null;
    }

    public static int getDrawable(String name) {
        if (name == null) {
            return 0;
        }
        return ApplicationLoader.applicationContext.getResources().getIdentifier(name, "drawable", ApplicationLoader.applicationContext.getPackageName());
    }

    // ---------------------------------------------------------------------------------
    // reflection

    public static ClassLoader getClassLoader() {
        return PluginBridge.class.getClassLoader();
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
            }
        }
        throw new NoSuchFieldException(cls.getName() + "." + name);
    }

    public static Object getField(Object obj, String name) throws Exception {
        return findField(obj.getClass(), name).get(obj);
    }

    public static void setField(Object obj, String name, Object value) throws Exception {
        findField(obj.getClass(), name).set(obj, value);
    }

    public static Object getStaticField(Class<?> cls, String name) throws Exception {
        return findField(cls, name).get(null);
    }

    public static void setStaticField(Class<?> cls, String name, Object value) throws Exception {
        findField(cls, name).set(null, value);
    }

    // ---------------------------------------------------------------------------------
    // method hooks (Pine)

    private static synchronized void ensurePine() {
        if (pineReady) {
            return;
        }
        PineConfig.debug = false;
        PineConfig.debuggable = false;
        Pine.ensureInitialized();
        Pine.disableJitInline();
        pineReady = true;
    }

    public static MethodHook.Unhook hookMethod(String pluginId, Member method, PyObject hook, boolean before, boolean after) {
        ensurePine();
        return Pine.hook(method, new PluginMethodHook(pluginId, hook, before, after));
    }

    private static class PluginMethodHook extends MethodHook {
        private final String pluginId;
        private final PyObject hook;
        private final boolean before, after;

        PluginMethodHook(String pluginId, PyObject hook, boolean before, boolean after) {
            this.pluginId = pluginId;
            this.hook = hook;
            this.before = before;
            this.after = after;
        }

        @Override
        public void beforeCall(Pine.CallFrame frame) {
            if (before) {
                try {
                    hook.callAttr("before_hooked_method", frame);
                } catch (Throwable e) {
                    PluginsController.reportError(pluginId, "before_hooked_method: " + e);
                }
            }
        }

        @Override
        public void afterCall(Pine.CallFrame frame) {
            if (after) {
                try {
                    hook.callAttr("after_hooked_method", frame);
                } catch (Throwable e) {
                    PluginsController.reportError(pluginId, "after_hooked_method: " + e);
                }
            }
        }
    }
}
