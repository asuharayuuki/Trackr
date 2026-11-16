package com.trackr.ui.platform

import android.content.Context

/**
 * 实时活动（Android 16 Live Update、小米焦点通知、OPPO 流体云）的设备判断和设置页跳转。
 *
 * 页面不直接用它：「我的」页读 commonMain 的 [LiveUpdateAccess]，`:androidApp` 的 `AndroidLiveUpdateAccess` 再经这个接口
 * 调厂商适配对象（实现 `AndroidLiveUpdateSettings` 原样转调 `LiveUpdatePromotion` / `XiaomiFocusAdapter` / `OppoFluidCloudAdapter`）。
 * 方法和厂商适配对象的调用一一对应，[Context] 由调用方传入。
 */
interface LiveUpdateSettings {
    /** `LiveUpdatePromotion.isPromotionSupported()` */
    fun isPromotionSupported(): Boolean

    /** `LiveUpdatePromotion.canPromote(context)` */
    fun canPromote(context: Context): Boolean

    /** `LiveUpdatePromotion.openPromotionSettings(context)` */
    fun openPromotionSettings(context: Context): Boolean

    /** `LiveUpdatePromotion.openAppNotificationSettings(context)` */
    fun openAppNotificationSettings(context: Context): Boolean

    /**
     * `XiaomiFocusAdapter.isFocusSupported(context)`：这台机器到底有没有焦点通知 / 超级岛。
     * 「有没有这功能」和「用户开没开」是两件事——MIUI 全系没有，`canShowXiaomiFocus` 恒为 false，不能当没开。
     */
    fun isXiaomiFocusSupported(context: Context): Boolean

    /** `XiaomiFocusAdapter.canShowFocus(context)` */
    fun canShowXiaomiFocus(context: Context): Boolean

    /** `XiaomiFocusAdapter.openNotificationSettings(context)` */
    fun openXiaomiNotificationSettings(context: Context)

    /** `OppoFluidCloudAdapter.isColorOs()` */
    fun isColorOs(): Boolean

    /** `OppoFluidCloudAdapter.openFluidCloudSettings(context)` */
    fun openFluidCloudSettings(context: Context): Boolean
}
