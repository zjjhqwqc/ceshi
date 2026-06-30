package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_InitPackageResources;

/**
 * Interface for hooks that want to modify resources of an app.
 */
public interface IXposedHookInitPackageResources {
    /**
     * Called when resources for an app are initialized.
     */
    void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable;
}
