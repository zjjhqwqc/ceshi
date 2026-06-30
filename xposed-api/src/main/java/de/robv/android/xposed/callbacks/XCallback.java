package de.robv.android.xposed.callbacks;

/**
 * Base class for Xposed callbacks.
 */
public abstract class XCallback {
    /**
     * Base parameter class for callbacks.
     */
    public static abstract class Param {
    }

    /**
     * Called to invoke the callback.
     */
    protected abstract void call(Param param) throws Throwable;
}
