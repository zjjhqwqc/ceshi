package de.robv.android.xposed.callbacks;

/**
 * Callback for hooking into the loading of a package.
 */
public abstract class XC_LoadPackage extends XCallback {
    /**
     * Parameters for handleLoadPackage.
     */
    public static final class LoadPackageParam extends XCallback.Param {
        /** The name of the package. */
        public String packageName;
        /** The name of the process. */
        public String processName;
        /** The class loader for the package. */
        public ClassLoader classLoader;
        /** Whether this is the first (main) application in the process. */
        public boolean isFirstApplication;
    }

    @Override
    protected void call(Param param) throws Throwable {
        handleLoadPackage((LoadPackageParam) param);
    }

    /**
     * Called when an app is loaded.
     */
    protected abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
