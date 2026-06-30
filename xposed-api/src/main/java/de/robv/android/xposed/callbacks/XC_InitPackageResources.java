package de.robv.android.xposed.callbacks;

/**
 * Callback for hooking into the initialization of an app's resources.
 */
public abstract class XC_InitPackageResources extends XCallback {
    /**
     * Parameters for handleInitPackageResources.
     */
    public static final class InitPackageResourcesParam extends XCallback.Param {
        /** The name of the package. */
        public String packageName;
        /** The resources object. */
        public Object res;
    }

    @Override
    protected void call(Param param) throws Throwable {
        handleInitPackageResources((InitPackageResourcesParam) param);
    }

    protected abstract void handleInitPackageResources(InitPackageResourcesParam resparam) throws Throwable;
}
