package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyObject;

import org.telegram.messenger.plugins.PluginsController;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;

// Calls before_hooked_method / after_hooked_method of a Python object (exteraGram API).
// Hook filters are accepted for compatibility and not evaluated: the Python side runs.
public class PyMethodHook extends XC_MethodHook {

    protected final String pluginId;
    protected final PyObject hook;
    private final boolean hasBefore, hasAfter;

    public PyMethodHook(String pluginId, PyObject hook) {
        this(pluginId, hook, PRIORITY_DEFAULT);
    }

    public PyMethodHook(String pluginId, PyObject hook, int priority) {
        super(priority);
        this.pluginId = pluginId;
        this.hook = hook;
        hasBefore = hasCallable(hook, "before_hooked_method");
        hasAfter = hasCallable(hook, "after_hooked_method");
    }

    static boolean hasCallable(PyObject obj, String name) {
        try {
            PyObject attr = obj.get(name);
            return attr != null && !"None".equals(attr.repr());
        } catch (Throwable e) {
            return false;
        }
    }

    public void setBeforeHookedFilters(ArrayList<?> filters) {
    }

    public void setAfterHookedFilters(ArrayList<?> filters) {
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (hasBefore) {
            call("before_hooked_method", param);
        }
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        if (hasAfter) {
            call("after_hooked_method", param);
        }
    }

    private void call(String name, MethodHookParam param) {
        try {
            hook.callAttr(name, param);
        } catch (Throwable e) {
            PluginsController.reportError(pluginId, name + ": " + e);
        }
    }
}
