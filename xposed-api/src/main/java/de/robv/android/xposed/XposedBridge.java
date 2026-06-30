package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * Core Xposed API stubs for compilation purposes.
 * These are compileOnly stubs -- method bodies throw UnsupportedOperationException at runtime.
 */
public class XposedBridge {

    /**
     * Logs an error message.
     */
    public static void log(String text) {
        throw new UnsupportedOperationException("XposedBridge is a compile-only stub");
    }

    /**
     * Logs an error with a throwable.
     */
    public static void log(String text, Throwable t) {
        throw new UnsupportedOperationException("XposedBridge is a compile-only stub");
    }

    /**
     * Hooks a method.
     */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("XposedBridge is a compile-only stub");
    }

    /**
     * Hooks all methods with the given name in the specified class.
     */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("XposedBridge is a compile-only stub");
    }

    /**
     * Hooks all constructors of the specified class.
     */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("XposedBridge is a compile-only stub");
    }

    /**
     * Unhooks a previously hooked method.
     */
    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("XposedBridge is a compile-only stub");
    }

    /**
     * Abstract class for method hooks. Subclass and override beforeHookedMethod and/or afterHookedMethod.
     */
    public static abstract class XC_MethodHook {
        /**
         * Called before the hooked method is executed.
         */
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

        /**
         * Called after the hooked method has returned.
         */
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

        /**
         * Parameter object for beforeHookedMethod and afterHookedMethod.
         */
        public static final class MethodHookParam {
            /** The method that was hooked. */
            public Member method;
            /** The instance on which the method was invoked (null for static methods). */
            public Object thisObject;
            /** The arguments passed to the method. */
            public Object[] args;
            /** The return value of the method (after execution, modifiable in afterHookedMethod). */
            private Object result;
            /** The throwable thrown by the method (after execution). */
            private Throwable throwable;
            /** Indicates whether the result has been set. */
            private boolean resultOrThrowableHandled = false;
            /** Indicates whether the method should return early (set in beforeHookedMethod). */
            public boolean returnEarly = false;

            /**
             * Gets the return value.
             */
            public Object getResult() {
                return result;
            }

            /**
             * Gets the throwable.
             */
            public Throwable getThrowable() {
                return throwable;
            }

            /**
             * Sets the return value and marks it as handled.
             */
            public void setResult(Object result) {
                this.result = result;
                this.resultOrThrowableHandled = true;
                this.throwable = null;
            }

            /**
             * Sets the throwable and marks it as handled.
             */
            public void setThrowable(Throwable throwable) {
                this.throwable = throwable;
                this.resultOrThrowableHandled = true;
                this.result = null;
            }

            /**
             * Returns the result or throws the throwable.
             */
            public Object getResultOrThrowable() throws Throwable {
                if (throwable != null) {
                    throw throwable;
                }
                return result;
            }
        }

        /**
         * Interface to unhook a method.
         */
        public interface Unhook {
            /**
             * Removes this hook.
             */
            void unhook();
        }
    }

    /**
     * Helper class to replace a method entirely.
     */
    public static class XC_MethodReplacement extends XC_MethodHook {
        private final boolean returnConstant;
        private final Object constantValue;

        /**
         * Creates a replacement that always returns null.
         */
        protected XC_MethodReplacement() {
            this.returnConstant = false;
            this.constantValue = null;
        }

        private XC_MethodReplacement(boolean returnConstant, Object constantValue) {
            this.returnConstant = returnConstant;
            this.constantValue = constantValue;
        }

        /**
         * Returns a replacement that always returns the given constant value.
         */
        public static XC_MethodReplacement returnConstant(final Object value) {
            return new XC_MethodReplacement(true, value);
        }

        /**
         * Returns a replacement that does nothing and returns null.
         */
        public static XC_MethodReplacement DO_NOTHING() {
            return new XC_MethodReplacement(false, null);
        }

        /**
         * Called instead of the original method.
         */
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (returnConstant) {
                param.setResult(constantValue);
            } else {
                param.setResult(null);
            }
        }
    }

    /**
     * Xposed logging utility.
     */
    public static final class Log {
        private Log() {}

        public static int e(String msg) { return 0; }
        public static int e(String msg, Throwable tr) { return 0; }
        public static int w(String msg) { return 0; }
        public static int w(String msg, Throwable tr) { return 0; }
        public static int i(String msg) { return 0; }
        public static int i(String msg, Throwable tr) { return 0; }
        public static int d(String msg) { return 0; }
        public static int d(String msg, Throwable tr) { return 0; }
    }
}
