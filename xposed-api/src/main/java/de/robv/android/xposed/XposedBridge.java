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
