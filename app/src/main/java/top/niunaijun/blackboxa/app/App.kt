package top.niunaijun.blackboxa.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.util.HiddenApiBypass

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/4/29 21:21
 */
class App : Application() {

    companion object {

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private lateinit var mContext: Context

        @JvmStatic
        fun getContext(): Context {
            return mContext
        }
    }

    override fun attachBaseContext(base: Context?) {
        // 首先调用 super，确保基础上下文已设置
        super.attachBaseContext(base)
        
        if (base == null) {
            Log.e("App", "attachBaseContext: base is null")
            return
        }
        
        try {
            // 【关键修复】在任何系统 API 调用之前，先绕过隐藏 API 限制
            // 参考 VirtualApp 商业版：在 Application.attachBaseContext 最开始
            // 调用 VMRuntime.setHiddenApiExemptions() 豁免所有隐藏 API
            // 这是 Android 15/16 能获取完整已安装应用列表的前提条件
            HiddenApiBypass.bypassHiddenApiRestrictions()
        } catch (e: Exception) {
            Log.e("App", "HiddenApiBypass failed: ${e.message}")
            // 继续执行，不要因为隐藏 API 绕过失败而崩溃
        }

        // 设置上下文
        mContext = base
        
        // 初始化 BlackBoxCore - 使用 try-catch 包裹每个调用
        try {
            val blackBoxCore = BlackBoxCore.get()
            if (blackBoxCore != null) {
                try {
                    blackBoxCore.closeCodeInit()
                } catch (e: Exception) {
                    Log.e("App", "Error in closeCodeInit: ${e.message}")
                }

                try {
                    blackBoxCore.onBeforeMainApplicationAttach(this, base)
                } catch (e: Exception) {
                    Log.e("App", "Error in onBeforeMainApplicationAttach: ${e.message}")
                }
            } else {
                Log.e("App", "BlackBoxCore.get() returned null")
            }
        } catch (e: Exception) {
            Log.e("App", "Error initializing BlackBoxCore: ${e.message}")
        }
        
        try {
            AppManager.doAttachBaseContext(base)
        } catch (e: Exception) {
            Log.e("App", "Error in doAttachBaseContext: ${e.message}")
        }

        try {
            val blackBoxCore = BlackBoxCore.get()
            if (blackBoxCore != null) {
                try {
                    blackBoxCore.onAfterMainApplicationAttach(this, base)
                } catch (e: Exception) {
                    Log.e("App", "Error in onAfterMainApplicationAttach: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("App", "Error in onAfterMainApplicationAttach outer: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            AppManager.doOnCreate(mContext)
        } catch (e: Exception) {
            Log.e("App", "Error in onCreate: ${e.message}")
        }
    }
}
