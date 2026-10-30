package de.robv.android.xposed;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.plugins.PluginBridge;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

public final class XposedBridge {

    private XposedBridge() {
    }

    public static XC_MethodHook.Unhook hookMethod(Member method, XC_MethodHook callback) {
        PluginBridge.ensurePine();
        MethodHook.Unhook unhook = Pine.hook(method, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame frame) {
                XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam(frame);
                try {
                    callback.callBefore(param);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                param.sync();
            }

            @Override
            public void afterCall(Pine.CallFrame frame) {
                XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam(frame);
                try {
                    callback.callAfter(param);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                param.sync();
            }
        });
        return callback.new Unhook(method, unhook);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> cls, String name, XC_MethodHook callback) {
        HashSet<XC_MethodHook.Unhook> set = new HashSet<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                set.add(hookMethod(m, callback));
            }
        }
        return set;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> cls, XC_MethodHook callback) {
        HashSet<XC_MethodHook.Unhook> set = new HashSet<>();
        for (Member c : cls.getDeclaredConstructors()) {
            set.add(hookMethod(c, callback));
        }
        return set;
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Exception {
        return Pine.invokeOriginalMethod(method, thisObject, args);
    }

    public static void log(String text) {
        FileLog.d("xposed: " + text);
    }

    public static void log(Throwable t) {
        FileLog.e(t);
    }
}
