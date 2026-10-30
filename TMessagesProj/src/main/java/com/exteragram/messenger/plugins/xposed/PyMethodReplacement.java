package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyObject;

import org.telegram.messenger.plugins.PluginsController;

import de.robv.android.xposed.XC_MethodReplacement;

// Replaces a method body with replace_hooked_method of a Python object (exteraGram API).
public class PyMethodReplacement extends XC_MethodReplacement {

    protected final String pluginId;
    protected final PyObject hook;

    public PyMethodReplacement(String pluginId, PyObject hook) {
        this(pluginId, hook, PRIORITY_DEFAULT);
    }

    public PyMethodReplacement(String pluginId, PyObject hook, int priority) {
        super(priority);
        this.pluginId = pluginId;
        this.hook = hook;
    }

    @Override
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        try {
            PyObject result = hook.callAttr("replace_hooked_method", param);
            return result == null ? null : result.toJava(Object.class);
        } catch (Throwable e) {
            PluginsController.reportError(pluginId, "replace_hooked_method: " + e);
            throw e;
        }
    }
}
