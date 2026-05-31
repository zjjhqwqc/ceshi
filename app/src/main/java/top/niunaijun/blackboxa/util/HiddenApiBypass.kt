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

                val setHiddenApiExemptionsMethod: Method =
                    vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                setHiddenApiExemptionsMethod.isAccessible = true

                // 传入空数组表示豁免所有类的隐藏 API 限制
                setHiddenApiExemptionsMethod.invoke(vmRuntime, emptyArray<String>())

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
}
