package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Stub for XC_MethodHook as a top-level class.
 * Delegates to XposedBridge.XC_MethodHook for compatibility.
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean resultOrThrowableHandled = false;
        public boolean returnEarly = false;

        public Object getResult() { return result; }
        public Throwable getThrowable() { return throwable; }
        public void setResult(Object result) {
            this.result = result;
            this.resultOrThrowableHandled = true;
            this.throwable = null;
        }
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.resultOrThrowableHandled = true;
            this.result = null;
        }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    public interface Unhook {
        void unhook();
    }
}
