package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Interface for hooks to be loaded into the target app's class loader.
 */
public interface IXposedHookLoadPackage {
    /**
     * Called when an app is loaded.
     */
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
