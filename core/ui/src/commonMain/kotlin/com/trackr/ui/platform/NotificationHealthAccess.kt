package com.trackr.ui.platform

/**
 * 「我的」页的通知可靠性体检（第 37 步）。
 *
 * 用户抱怨「我明明开了提醒却没响」，八成是系统那边把渠道降了、或者通知权限被拒，
 * App 里却看不出来。这个接口把三件能查的事查出来，查不到的那几件（锁屏通知开关、横幅子开关）不假装能查。
 *
 * Android 实现在 `:androidApp` 的 PlatformBridgeModule；iOS 不绑定，那一行不显示。
 */
interface NotificationHealthAccess {
    /** Native asynchronous permission changes, when available. */
    val changes: kotlinx.coroutines.flow.StateFlow<NotificationHealth>? get() = null


    /** 现在的状态；每次回到前台重查（用户可能刚去过系统设置） */
    fun status(): NotificationHealth

    /** 打开「公交到站与进站强提醒」渠道的设置页；渠道不存在时退到应用通知设置 */
    fun openAlertChannelSettings()
}

/**
 * @param notificationsEnabled 通知总开关（`POST_NOTIFICATIONS` 没给 / 被系统关掉时为 false）
 * @param alertChannelAudible 「公交到站与进站强提醒」渠道还在不在出声：低于 `IMPORTANCE_HIGH` 就没有横幅和声音
 * @param liveChannelOff 「实时在途追踪与进度」渠道被整个关掉（进度通知不会出现）
 */
data class NotificationHealth(
    val notificationsEnabled: Boolean = true,
    val alertChannelAudible: Boolean = true,
    val liveChannelOff: Boolean = false
) {
    /** 三项都正常。任何一项不正常，「不让用户错过车」这条链路就断了一截 */
    val healthy: Boolean get() = notificationsEnabled && alertChannelAudible && !liveChannelOff
}
