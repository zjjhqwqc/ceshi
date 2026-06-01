package top.niunaijun.blackboxa.util

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * Hidden API 绕过工具类
 *
 * 参考 VirtualApp 商业版（分身空间）的实现方案：
 * 1. 通过反射调用 VMRuntime.setHiddenApiExemptions() 绕过 Android 9+ 的隐藏 API 限制
 * 2. 在 Android 15/16 上，系统进一步收紧了 PMS 的访问限制，
 *    需要豁免隐藏 API 才能通过反射获取完整已安装应用列表
 *
 * VirtualApp 商业版在 classes2.dex 和 classes3.dex 中均使用了
 * VMRuntime + setHiddenApiExemptions 来绕过限制。
 */
object HiddenApiBypass {
    private const val TAG = "HiddenApiBypass"

    @Volatile
    private var isBypassed = false

    /**
     * 绕过 Android 隐藏 API 限制
     * 必须在任何系统 API 调用之前执行（在 Application.attachBaseContext 中）
     *
     * 原理：通过反射调用 dalvik.system.VMRuntime.setHiddenApiExemptions()
     * 传入空字符串数组，表示豁免所有隐藏 API 的限制检查
     *
     * 注意：在 Android 15/16 上，此方法可能部分失效，需要配合其他策略
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun bypassHiddenApiRestrictions() {
        if (isBypassed) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Android 9+ 需要绕过隐藏 API 限制
                val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
                val getRuntimeMethod: Method = vmRuntimeClass.getDeclaredMethod("getRuntime")
                getRuntimeMethod.isAccessible = true
                val vmRuntime = getRuntimeMethod.invoke(null)

                // Android 15+ (API 35+) 可能需要特殊处理
                if (Build.VERSION.SDK_INT >= 35) {
                    // 尝试新的豁免方法
                    tryBypassAndroid15Plus(vmRuntimeClass, vmRuntime)
                } else {
                    // Android 9-14 使用标准方法
                    val setHiddenApiExemptionsMethod: Method =
                        vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                    setHiddenApiExemptionsMethod.isAccessible = true
                    setHiddenApiExemptionsMethod.invoke(vmRuntime, emptyArray<String>())
                }

                isBypassed = true
                Log.d(TAG, "Hidden API bypass successful on SDK ${Build.VERSION.SDK_INT}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bypass hidden API restrictions: ${e.message}")
            // 尝试备用方案
            tryBackupBypass()
        }
    }

    /**
     * Android 15+ (API 35+) 的特殊处理
     * 尝试多种方法绕过限制
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun tryBypassAndroid15Plus(vmRuntimeClass: Class<*>, vmRuntime: Any?) {
        if (vmRuntime == null) return

        var success = false

        // 方法1: 尝试 setHiddenApiExemptions
        try {
            val setHiddenApiExemptionsMethod = vmRuntimeClass.getDeclaredMethod(
                "setHiddenApiExemptions", Array<String>::class.java
            )
            setHiddenApiExemptionsMethod.isAccessible = true
            setHiddenApiExemptionsMethod.invoke(vmRuntime, emptyArray<String>())
            success = true
            Log.d(TAG, "Android 15+ bypass method 1 successful")
        } catch (e: Exception) {
            Log.w(TAG, "Android 15+ bypass method 1 failed: ${e.message}")
        }

        // 方法2: 尝试 setHiddenApiEnforcementPolicy
        if (!success) {
            try {
                val setMethod = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiEnforcementPolicy",
                    Int::class.javaPrimitiveType
                )
                setMethod.isAccessible = true
                // 0 = no enforcement (允许所有隐藏 API)
                setMethod.invoke(vmRuntime, 0)
                success = true
                Log.d(TAG, "Android 15+ bypass method 2 successful")
            } catch (e: Exception) {
                Log.w(TAG, "Android 15+ bypass method 2 failed: ${e.message}")
            }
        }

        // 方法3: 尝试通过元数据豁免
        if (!success) {
            try {
                // 尝试调用 native 方法或设置系统属性
                val addHiddenApiExemptionsMethod = vmRuntimeClass.getDeclaredMethod(
                    "addHiddenApiExemptions", Array<String>::class.java
                )
                addHiddenApiExemptionsMethod.isAccessible = true
                addHiddenApiExemptionsMethod.invoke(vmRuntime, arrayOf(""))
                success = true
                Log.d(TAG, "Android 15+ bypass method 3 successful")
            } catch (e: Exception) {
                Log.w(TAG, "Android 15+ bypass method 3 failed: ${e.message}")
            }
        }

        if (!success) {
            Log.w(TAG, "All Android 15+ bypass methods failed, continuing without full bypass")
        }
    }

    /**
     * 备用绕过方案
     * 通过设置元数据反射豁免
     */
    private fun tryBackupBypass() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
                val getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime")
                getRuntimeMethod.isAccessible = true
                val vmRuntime = getRuntimeMethod.invoke(null)

                // 尝试使用 setHiddenApiEnforcementPolicy
                try {
                    val setMethod = vmRuntimeClass.getDeclaredMethod(
                        "setHiddenApiEnforcementPolicy",
                        Int::class.javaPrimitiveType
                    )
                    setMethod.isAccessible = true
                    // 0 = no enforcement (允许所有隐藏 API)
                    setMethod.invoke(vmRuntime, 0)
                    isBypassed = true
                    Log.d(TAG, "Backup bypass (enforcement policy) successful")
                } catch (e2: Exception) {
                    Log.e(TAG, "Backup bypass also failed: ${e2.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backup bypass error: ${e.message}")
        }
    }

    /**
     * 检查是否成功绕过隐藏 API 限制
     */
    fun isBypassed(): Boolean {
        return isBypassed
    }
}
