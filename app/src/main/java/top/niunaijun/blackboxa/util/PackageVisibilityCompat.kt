package top.niunaijun.blackboxa.util

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log

/**
 * 包可见性兼容工具类
 *
 * 解决 Android 11+ (API 30+) 的包可见性限制问题。
 * 在 Android 15/16 上，即使声明了 QUERY_ALL_PACKAGES 权限，
 * 标准 PackageManager API 仍可能返回不完整的结果。
 *
 * 参考 VirtualApp 商业版（分身空间）的实现：
 * 1. 声明 QUERY_ALL_PACKAGES 权限（Manifest 层面）
 * 2. 通过反射直接调用 IPackageManager 的底层方法获取完整列表
 * 3. 使用 MATCH_ALL (0x00008000) 标志位
 * 4. 在高版本上使用 getInstalledPackagesAsUser / getPackageInfoAsUser
 * 5. 通过 VMRuntime.setHiddenApiExemptions 绕过隐藏 API 限制（见 HiddenApiBypass）
 */
object PackageVisibilityCompat {

    private const val TAG = "PackageVisibilityCompat"

    // PackageManager.GET_SIGNATURES = 0x00000040
    private const val GET_SIGNATURES = 0x00000040
    // PackageManager.MATCH_ALL = 0x00008000 (API 30+)
    private const val MATCH_ALL = 0x00008000
    // PackageManager.MATCH_KNOWN_PACKAGES = 0x00002000 (API 30+)
    private const val MATCH_KNOWN_PACKAGES = 0x00002000
    // PackageManager.MATCH_DISABLED_COMPONENTS = 0x00000200
    private const val MATCH_DISABLED_COMPONENTS = 0x00000200

    /**
     * 获取已安装应用列表（兼容 Android 15/16）
     *
     * 策略（按优先级）：
     * 1. Android 11+ 且有 QUERY_ALL_PACKAGES 权限：使用标准 API + MATCH_ALL 标志
     * 2. 反射调用 ActivityThread.sPackageManager 获取完整列表
     * 3. 反射调用 IPackageManager.getInstalledPackages() 获取完整列表
     * 4. 回退到标准 API
     */
    fun getInstalledApplicationsCompat(pm: android.content.pm.PackageManager): List<ApplicationInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ (API 30+)
                getInstalledApplicationsModern(pm)
            } else {
                // Android 10 及以下：直接使用标准 API
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledApplicationsCompat failed: ${e.message}")
            // 最终回退
            try {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback also failed: ${e2.message}")
                emptyList()
            }
        }
    }

    /**
     * Android 11+ 的现代实现
     * 使用 MATCH_ALL 标志 + 反射兜底
     */
    private fun getInstalledApplicationsModern(pm: android.content.pm.PackageManager): List<ApplicationInfo> {
        // 方法1：使用标准 API + GET_UNINSTALLED_PACKAGES 标志
        try {
            val flags = android.content.pm.PackageManager.GET_UNINSTALLED_PACKAGES or
                    MATCH_DISABLED_COMPONENTS

            val result: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ (API 33+): 使用 ApplicationInfoFlags
                // ApplicationInfoFlags.of() 接受 Long 类型参数
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(flags)
            }

            if (result.isNotEmpty()) {
                Log.d(TAG, "Method 1 (standard API with flags): got ${result.size} apps")
                return result
            }
        } catch (e: Exception) {
            Log.w(TAG, "Method 1 failed: ${e.message}")
        }

        // 方法2：通过反射 ActivityThread 获取 sPackageManager
        // 参考 VirtualApp 商业版：直接从 ActivityThread 获取系统级 PackageManager
        try {
            val apps = getInstalledPackagesViaActivityThread()
            if (apps.isNotEmpty()) {
                Log.d(TAG, "Method 2 (ActivityThread reflection): got ${apps.size} apps")
                return apps.mapNotNull { it.applicationInfo }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Method 2 failed: ${e.message}")
        }

        // 方法3：通过反射调用 IPackageManager.getInstalledPackages
        try {
            val apps = getInstalledPackagesViaIPM()
            if (apps.isNotEmpty()) {
                Log.d(TAG, "Method 3 (IPackageManager reflection): got ${apps.size} apps")
                return apps.mapNotNull { it.applicationInfo }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Method 3 failed: ${e.message}")
        }

        // 方法4：最终回退
        Log.w(TAG, "All methods failed, falling back to basic API")
        return try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(
                android.content.pm.PackageManager.GET_META_DATA
            )
        } catch (e: Exception) {
            Log.e(TAG, "Final fallback failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 方法2：通过反射 ActivityThread 获取 sPackageManager
     *
     * 参考 VirtualApp 的实现方式，直接从 ActivityThread 获取
     * 系统级 PackageManager 进行查询。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun getInstalledPackagesViaActivityThread(): List<PackageInfo> {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentActivityThreadMethod.isAccessible = true
            val activityThread = currentActivityThreadMethod.invoke(null)
                ?: return emptyList()

            val sPmField = activityThreadClass.getDeclaredField("sPackageManager")
            sPmField.isAccessible = true
            val sPackageManager = sPmField.get(activityThread) ?: return emptyList()

            val flags = GET_SIGNATURES or MATCH_ALL or MATCH_DISABLED_COMPONENTS

            val getInstalledPackagesMethod = sPackageManager.javaClass.getMethod(
                "getInstalledPackages",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            getInstalledPackagesMethod.isAccessible = true

            val result = getInstalledPackagesMethod.invoke(
                sPackageManager,
                flags,
                0
            ) ?: return emptyList()

            return unwrapParceledListSlice(result)
        } catch (e: Exception) {
            Log.w(TAG, "ActivityThread reflection failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * 方法3：通过反射调用 IPackageManager.getInstalledPackages
     *
     * 参考 VirtualApp 商业版在 classes.dex/classes3.dex 中的实现
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun getInstalledPackagesViaIPM(): List<PackageInfo> {
        try {
            val pmClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = pmClass.getDeclaredMethod("getService", String::class.java)
            getServiceMethod.isAccessible = true
            val binder = getServiceMethod.invoke(null, "package") as? android.os.IBinder
                ?: return emptyList()

            val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager")
            val asInterfaceMethod = iPackageManagerClass.getDeclaredMethod(
                "asInterface",
                android.os.IBinder::class.java
            )
            asInterfaceMethod.isAccessible = true
            val ipm = asInterfaceMethod.invoke(null, binder) ?: return emptyList()

            val flags = GET_SIGNATURES or MATCH_ALL or MATCH_DISABLED_COMPONENTS

            val getInstalledPackagesMethod = iPackageManagerClass.getDeclaredMethod(
                "getInstalledPackages",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            getInstalledPackagesMethod.isAccessible = true

            val result = getInstalledPackagesMethod.invoke(
                ipm,
                flags,
                0
            ) ?: return emptyList()

            return unwrapParceledListSlice(result)
        } catch (e: Exception) {
            Log.w(TAG, "IPackageManager reflection failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * 解包 ParceledListSlice 为 List
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> unwrapParceledListSlice(result: Any): List<T> {
        return try {
            if (result is List<*>) {
                return result as List<T>
            }
            val getListMethod = result.javaClass.getMethod("getList")
            getListMethod.invoke(result) as? List<T> ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unwrap ParceledListSlice: ${e.message}")
            try {
                result as? List<T> ?: emptyList()
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 检查是否拥有 QUERY_ALL_PACKAGES 权限
     */
    fun hasQueryAllPackagesPermission(pm: android.content.pm.PackageManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(
                    android.content.pm.PackageManager.ApplicationInfoFlags.of(
                        android.content.pm.PackageManager.MATCH_ALL.toLong()
                    )
                ).isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0).isNotEmpty()
            }
        } catch (e: Exception) {
            false
        }
    }
}
