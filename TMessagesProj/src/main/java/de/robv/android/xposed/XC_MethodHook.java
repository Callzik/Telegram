package de.robv.android.xposed;

import java.lang.reflect.Member;

import top.canyie.pine.Pine;

// Xposed-style hook callback, run on top of Pine (exteraGram plugins hook through this API).
public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    public final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    void callBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    void callAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    public static final class MethodHookParam {
        public final Member method;
        public Object thisObject;
        public Object[] args;
        final Pine.CallFrame frame;

        MethodHookParam(Pine.CallFrame frame) {
            this.frame = frame;
            this.method = frame.method;
            this.thisObject = frame.thisObject;
            this.args = frame.args;
        }

        void sync() {
            frame.thisObject = thisObject;
            frame.args = args;
        }

        public Object getResult() {
            return frame.getResult();
        }

        public void setResult(Object result) {
            frame.setResult(result);
        }

        public Throwable getThrowable() {
            return frame.getThrowable();
        }

        public boolean hasThrowable() {
            return frame.hasThrowable();
        }

        public void setThrowable(Throwable throwable) {
            frame.setThrowable(throwable);
        }

        public Object getResultOrThrowable() throws Throwable {
            return frame.getResultOrThrowable();
        }

        public Object invokeOriginalMethod() throws Exception {
            sync();
            return frame.invokeOriginalMethod();
        }
    }

    public class Unhook {
        private final Member hookMethod;
        private final top.canyie.pine.callback.MethodHook.Unhook unhook;

        Unhook(Member hookMethod, top.canyie.pine.callback.MethodHook.Unhook unhook) {
            this.hookMethod = hookMethod;
            this.unhook = unhook;
        }

        public Member getHookedMethod() {
            return hookMethod;
        }

        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        public void unhook() {
            unhook.unhook();
        }
    }
}
