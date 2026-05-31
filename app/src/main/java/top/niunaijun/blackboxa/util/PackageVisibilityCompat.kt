package top.niunaijun.blackboxa.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * 包可见性兼容工具类
 *
 * 解决 Android 11+ (API 30+) 的包可见性限制问题。
 * 在 Android 15/16 上，即使声明了 QUERY_ALL_PACKAGES 权限，
 * 标准 PackageManager API 仍可能返回不完整的结果。
 *
 * 参考 VirtualApp 商业版的实现：
 * 1. 声明 QUERY_ALL_PACKAGES 权限（Manifest 层面）
 * 2. 通过反射直接调用 IPackageManager 的底层方法获取完整列表
 * 3. 使用 MATCH_ALL (0x00008000) 标志位
 * 4. 在高版本上使用 getInstalledPackagesAsUser / getPackageInfoAsUser
 */
object PackageVisibilityCompat {

    private const val TAG = "PackageVisibilityCompat"

    // PackageManager.GET_SIGNATURES = 0x00000040
    private const val GET_SIGNATURES = 0x00000040
    // PackageManager.MATCH_ALL = 0x00008000 (API 30+)
    private const val MATCH_ALL = 0x00008000
    // PackageManager.MATCH_KNOWN_PACKAGES = 0x00002000 (API 30+)
    private const val MATCH_KNOWN_PACKAGES = 0x00002000
    // PackageManager.MATCH_DISABLED_COMPONENTS = 0x0000200
    private const val MATCH_DISABLED_COMPONENTS = 0x00000200

    /**
     * 获取已安装应用列表（兼容 Android 15/16）
     *
     * 策略（按优先级）：
     * 1. Android 11+ 且有 QUERY_ALL_PACKAGES 权限：使用标准 API + MATCH_ALL 标志
     * 2. 反射调用 IPackageManager.getInstalledPackages() 获取完整列表
     * 3. 回退到标准 API
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
        // 方法1：使用 PackageManager.GET_UNINSTALLED_PACKAGES (0x00008000 = MATCH_ALL on API 30+)
        // 在 API 30+ 上，0x00008000 对应 PackageManager.MATCH_ALL
        try {
            val flags = android.content.pm.PackageManager.GET_UNINSTALLED_PACKAGES or
                    MATCH_DISABLED_COMPONENTS

            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ (API 33+): PackageManager.getInstalledApplications 需要 PackageManager.ApplicationInfoFlags
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

        // 方法2：通过反射调用 IPackageManager.getInstalledPackages
        try {
            val apps = getInstalledPackagesViaReflection(pm)
            if (apps.isNotEmpty()) {
                Log.d(TAG, "Method 2 (IPackageManager reflection): got ${apps.size} apps")
                return apps.map { it.applicationInfo }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Method 2 failed: ${e.message}")
        }

        // 方法3：通过反射调用 ActivityThread 获取 sPackageManager
        try {
            val apps = getInstalledPackagesViaActivityThread()
            if (apps.isNotEmpty()) {
                Log.d(TAG, "Method 3 (ActivityThread reflection): got ${apps.size} apps")
                return apps.map { it.applicationInfo }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Method 3 failed: ${e.message}")
        }

        // 方法4：最终回退 - 使用标准 API 但不带特殊标志
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
     * 方法2：通过反射调用 IPackageManager.getInstalledPackages
     *
     * 参考 VirtualApp 商业版在 classes.dex/classes3.dex 中的实现：
     * 直接获取系统 IPackageManager 服务并调用其方法
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun getInstalledPackagesViaReflection(pm: android.content.pm.PackageManager): List<PackageInfo> {
        try {
            // 获取 IPackageManager 的 Binder 代理
            val pmClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = pmClass.getDeclaredMethod("getService", String::class.java)
            getServiceMethod.isAccessible = true
            val binder = getServiceMethod.invoke(null, "package") as? android.os.IBinder
                ?: return emptyList()

            // 获取 IPackageManager Stub
            val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager")
            val asInterfaceMethod = iPackageManagerClass.getDeclaredMethod(
                "asInterface",
                android.os.IBinder::class.java
            )
            asInterfaceMethod.isAccessible = true
            val ipm = asInterfaceMethod.invoke(null, binder) ?: return emptyList()

            // 调用 getInstalledPackages
            val flags = GET_SIGNATURES or MATCH_ALL or MATCH_DISABLED_COMPONENTS

            val getInstalledPackagesMethod = iPackageManagerClass.getDeclaredMethod(
                "getInstalledPackages",
                Int::class.javaPrimitiveType,
                String::class.java // callingPackage
            )
            getInstalledPackagesMethod.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val result = getInstalledPackagesMethod.invoke(
                ipm,
                flags,
                pm.context?.packageName ?: "top.niunaijun.blackboxa"
            ) as? List<PackageInfo>

            return result ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "IPackageManager reflection failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * 方法3：通过反射 ActivityThread 获取 sPackageManager
     *
     * 参考 VirtualApp 的实现方式，直接从 ActivityThread 获取
     * 系统级 PackageManager 进行查询
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun getInstalledPackagesViaActivityThread(): List<PackageInfo> {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentActivityThreadMethod.isAccessible = true
            val activityThread = currentActivityThreadMethod.invoke(null)
                ?: return emptyList()

            // 获取 sPackageManager
            val sPmField = activityThreadClass.getDeclaredField("sPackageManager")
            sPmField.isAccessible = true
            val sPackageManager = sPmField.get(activityThread) ?: return emptyList()

            // 调用 getInstalledPackages
            val flags = GET_SIGNATURES or MATCH_ALL or MATCH_DISABLED_COMPONENTS

            val getInstalledPackagesMethod = sPackageManager.javaClass.getMethod(
                "getInstalledPackages",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType // userId
            )
            getInstalledPackagesMethod.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val result = getInstalledPackagesMethod.invoke(
                sPackageManager,
                flags,
                0 // userId = 0 (主用户)
            ) as? List<PackageInfo>

            return result ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "ActivityThread reflection failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * 检查是否拥有 QUERY_ALL_PACKAGES 权限
     */
    fun hasQueryAllPackagesPermission(pm: android.content.pm.PackageManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
