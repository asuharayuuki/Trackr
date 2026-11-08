package com.trackr.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.catchingnow.icebox.sdk_client.IceBox
import com.trackr.core.model.TransitCodeProvider

/**
 * 乘车码唤醒与应用跳转工具类（支持支付宝乘车码与云闪付乘车码，并支持通过冰箱 IceBox 解冻已冻结应用）
 */
object TransitCodeHelper {

    private const val TAG = "TransitCodeHelper"

    const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
    const val PACKAGE_UNIONPAY = "com.unionpay"

    // 支付宝乘车码通用 Scheme（系统级秒开）
    const val SCHEME_ALIPAY_TRANSIT = "alipays://platformapi/startapp?appId=200011235"

    // 云闪付全国通用乘车码 Scheme（基于全国通用版 rnhtmlridingcode 路由，避免地区限定）
    const val SCHEME_UNIONPAY_TRANSIT = "upwallet://rn/rnhtmlridingcode"

    /**
     * 根据渠道打开乘车码。如果检测到应用已安装但被冻结，则调用冰箱 IceBox SDK 申请解冻并唤起乘车码。
     */
    fun openTransitCode(context: Context, provider: TransitCodeProvider): Boolean {
        val packageName = when (provider) {
            TransitCodeProvider.ALIPAY -> PACKAGE_ALIPAY
            TransitCodeProvider.UNIONPAY -> PACKAGE_UNIONPAY
            TransitCodeProvider.ASK_ALWAYS -> return false
        }
        val appName = when (provider) {
            TransitCodeProvider.ALIPAY -> "支付宝"
            TransitCodeProvider.UNIONPAY -> "云闪付"
            TransitCodeProvider.ASK_ALWAYS -> return false
        }

        // 1. 如果已安装且被冻结，触发冰箱解冻与打开流程
        if (isAppFrozen(context, packageName)) {
            return handleFrozenApp(context, provider, packageName, appName)
        }

        // 2. 未被冻结，直接正常唤起
        return when (provider) {
            TransitCodeProvider.ALIPAY -> openAlipayTransitCode(context)
            TransitCodeProvider.UNIONPAY -> openUnionPayTransitCode(context)
            TransitCodeProvider.ASK_ALWAYS -> false
        }
    }

    /**
     * 处理被冻结的应用解冻与唤起
     */
    private fun handleFrozenApp(
        context: Context,
        provider: TransitCodeProvider,
        packageName: String,
        appName: String
    ): Boolean {
        val iceBoxInstalled = isIceBoxInstalled(context)
        val workMode = runCatching { IceBox.queryWorkMode(context) }.getOrDefault(IceBox.WorkMode.MODE_NOT_AVAILABLE)

        if (!iceBoxInstalled && workMode == IceBox.WorkMode.MODE_NOT_AVAILABLE) {
            Toast.makeText(context, "${appName}已被冻结，但未检测到冰箱 (IceBox)，请手动解冻", Toast.LENGTH_LONG).show()
            return false
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            IceBox.SDK_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        return if (hasPermission) {
            // 已获授权，直接后台异步解冻并打开
            unfreezeAndLaunch(context, provider, packageName, appName)
            true
        } else {
            // 未获授权，启动透明 Activity 申请权限并解冻
            val intent = Intent(context, IceBoxDefrostActivity::class.java).apply {
                putExtra(IceBoxDefrostActivity.EXTRA_PROVIDER, provider.id)
                putExtra(IceBoxDefrostActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(IceBoxDefrostActivity.EXTRA_APP_NAME, appName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }
    }

    /**
     * 调用冰箱 SDK 解冻目标应用，并在成功后拉起对应的乘车码
     */
    fun unfreezeAndLaunch(
        context: Context,
        provider: TransitCodeProvider,
        packageName: String,
        appName: String,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        Toast.makeText(context, "正在通过冰箱解冻${appName}...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                IceBox.setAppEnabledSettings(context, true, packageName)
                // 留出适当时间让 PackageManager 与系统状态完成刷新
                Thread.sleep(200)
                Handler(Looper.getMainLooper()).post {
                    val opened = when (provider) {
                        TransitCodeProvider.ALIPAY -> openAlipayTransitCode(context)
                        TransitCodeProvider.UNIONPAY -> openUnionPayTransitCode(context)
                        TransitCodeProvider.ASK_ALWAYS -> false
                    }
                    onComplete?.invoke(opened)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to defrost $packageName with IceBox", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "解冻${appName}失败：${e.message}", Toast.LENGTH_LONG).show()
                    onComplete?.invoke(false)
                }
            }
        }.start()
    }

    /**
     * 唤起支付宝乘车码（系统级支持全局 Scheme 直达）
     */
    fun openAlipayTransitCode(context: Context): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME_ALIPAY_TRANSIT)).apply {
                setPackage(PACKAGE_ALIPAY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
            // 降级尝试：不限 package 直接打开
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME_ALIPAY_TRANSIT)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                return true
            } catch (_: Exception) {
                // 再次降级：直接打开支付宝 App 主页
                val launchIntent = context.packageManager.getLaunchIntentForPackage(PACKAGE_ALIPAY)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Toast.makeText(context, "已打开支付宝，请在首页使用乘车码", Toast.LENGTH_SHORT).show()
                    return true
                } else {
                    Toast.makeText(context, "未安装支付宝，无法打开乘车码", Toast.LENGTH_SHORT).show()
                    return false
                }
            }
        }
    }

    /**
     * 唤起云闪付乘车码（全国通用版直达）
     */
    fun openUnionPayTransitCode(context: Context): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME_UNIONPAY_TRANSIT)).apply {
                setPackage(PACKAGE_UNIONPAY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return true
        } catch (_: Exception) {
            // 降级尝试：不限定 package 直接调用系统解析
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SCHEME_UNIONPAY_TRANSIT)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                return true
            } catch (_: Exception) {
                // 降级尝试：若 Scheme 无法直接拉起，尝试直接打开云闪付 App
                val launchIntent = context.packageManager.getLaunchIntentForPackage(PACKAGE_UNIONPAY)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Toast.makeText(context, "已打开云闪付，请在首页使用乘车码", Toast.LENGTH_SHORT).show()
                    return true
                } else {
                    Toast.makeText(context, "未安装云闪付客户端，无法打开乘车码", Toast.LENGTH_SHORT).show()
                    return false
                }
            }
        }
    }

    /**
     * 检查某个包名对应的 App 是否已安装（包含处于停用/隐藏/冻结状态）
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        } else {
            PackageManager.GET_UNINSTALLED_PACKAGES
        }
        return try {
            context.packageManager.getPackageInfo(packageName, flags)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查某个应用是否已安装但处于被冻结状态（被 pm disable 或 pm hide）
     */
    fun isAppFrozen(context: Context, packageName: String): Boolean {
        return try {
            val state = IceBox.getAppEnabledSetting(context, packageName)
            state != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查是否安装了冰箱 (IceBox)
     */
    fun isIceBoxInstalled(context: Context): Boolean = isAppInstalled(context, IceBox.PACKAGE_NAME)

    /**
     * 检查是否安装了支付宝（含冻结状态）
     */
    fun isAlipayInstalled(context: Context): Boolean = isAppInstalled(context, PACKAGE_ALIPAY)

    /**
     * 检查是否安装了云闪付（含冻结状态）
     */
    fun isUnionPayInstalled(context: Context): Boolean = isAppInstalled(context, PACKAGE_UNIONPAY)
}
