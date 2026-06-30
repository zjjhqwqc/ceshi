package de.robv.android.xposed;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

/**
 * Xposed helper methods stub for compilation purposes.
 */
public class XposedHelpers {

    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz,
            String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(String className, ClassLoader classLoader,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callStaticMethod(String className, ClassLoader classLoader,
            String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object newInstance(Class<?> clazz, Class<?>[] parameterTypes, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object newInstance(Class<?> clazz) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getStaticObjectField(String className, ClassLoader classLoader, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static int getIntField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setStaticObjectField(String className, ClassLoader classLoader,
            String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Field findFieldByType(Class<?> clazz, Class<?> fieldType) {
        throw new UnsupportedOperationException("stub");
    }

    public static Method findMethodExact(String className, ClassLoader classLoader,
            String methodName, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }

    public static Constructor<?> findConstructorBestMatch(Class<?> clazz, Class<?>... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }
}
