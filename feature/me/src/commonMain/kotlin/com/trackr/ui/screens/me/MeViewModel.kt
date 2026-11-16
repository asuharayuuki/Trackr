package com.trackr.ui.screens.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.domain.platform.BoardingTraceLog
import com.trackr.core.model.EngineMode
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.RailMode
import com.trackr.core.model.ThemeMode
import com.trackr.core.model.UiMode
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.ui.platform.LiveUpdateAccess
import com.trackr.ui.platform.LiveUpdateStatus
import com.trackr.ui.platform.NotificationHealth
import com.trackr.ui.platform.NotificationHealthAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「我的」页状态（v3 分组：出行、通知、地图、关于）。
 *
 * @param alertThresholdStops 到站前提醒：车离上车站还有几站时提醒（1–3）
 * @param remindersSubtitle 「定时提醒」那一行的副标题（最早一条开着的提醒）；没有提醒为 null
 * @param liveUpdate 实时活动通知支持没支持、开没开；平台没有这项（iOS）或实时通知关着时为 null，不显示那一行
 * @param notificationHealth 通知可靠性体检；全正常或平台没有这项（iOS）时为 null，不显示那一行
 * @param amapKeyStatus 「高德 Key」那一行的状态文字
 * @param versionName 「关于」那一行显示的版本号；读不到为 null
 * @param showTransitCodeButton 「乘车码」按钮显不显示：首页悬浮按钮和线路页、站点页底栏的圆钮都听它（`SettingsRepository.showTransitCodeButton`）
 * @param autoStopAfterBoarding 上车后自动结束追踪（issue #32）：单线路和一起等判出已上车就结束，路线不受影响
 * @param canExportBoardingTrace 有没有「导出上车判定记录」那一行（issue #49）：平台没有判定记录（iOS）时不显示
 * @param voiceAlerts 语音播报提醒（issue #52）：追踪提醒同时朗读出来
 * @param elderMode 长辈版开关（`SettingsRepository.uiMode` 是不是 Elder）
 */
data class MeUiState(
    val engineMode: EngineMode = EngineMode.CHELAILE,
    val preferredTransitCode: TransitCodeProvider = TransitCodeProvider.ASK_ALWAYS,
    val showTransitCodeButton: Boolean = true,
    val autoStopAfterBoarding: Boolean = true,
    val canExportBoardingTrace: Boolean = false,
    val voiceAlerts: Boolean = false,
    val alertThresholdStops: Int = 2,
    val reminders: List<ScheduledReminder> = emptyList(),
    val remindersSubtitle: String? = null,
    val isLiveNotificationEnabled: Boolean = true,
    val showOnLockScreen: Boolean = true,
    val liveUpdate: LiveUpdateStatus? = null,
    /** 只在系统那边确实断了（没权限 / 渠道被降级）时才有值，正常时 null */
    val notificationHealth: NotificationHealth? = null,
    val amapKeyStatus: String = amapKeyStatusText(hasMapKey = false, canShowMap = false),
    val versionName: String? = null,
    val elderMode: Boolean = false,
    val railMode: RailMode = RailMode.Horizontal,
    val themeMode: ThemeMode = ThemeMode.System,
    val userMessages: List<UserMessage> = emptyList()
)

/** 「我的」页的事件。`Open*` 由 Route 处理（导航），其余交给 [MeViewModel.onEvent] */
sealed interface MeEvent {
    data class SetEngineMode(val mode: EngineMode) : MeEvent
    data class SetTransitCode(val provider: TransitCodeProvider) : MeEvent
    data class SetShowTransitCodeButton(val visible: Boolean) : MeEvent
    data class SetAutoStopAfterBoarding(val enabled: Boolean) : MeEvent
    /** 把判上车的判定记录交给系统分享面板（issue #49） */
    data object ExportBoardingTrace : MeEvent
    data class SetVoiceAlerts(val enabled: Boolean) : MeEvent
    data class SetAlertThresholdStops(val stops: Int) : MeEvent
    data class ToggleReminder(val id: String, val enabled: Boolean) : MeEvent
    data class SetLiveNotificationEnabled(val enabled: Boolean) : MeEvent
    data class SetShowOnLockScreen(val enabled: Boolean) : MeEvent
    data object OpenLiveUpdateSettings : MeEvent

    /** 到站强提醒被系统降级 / 关掉时，那一行的「去开启」 */
    data object OpenAlertChannelSettings : MeEvent
    data class SetElderMode(val enabled: Boolean) : MeEvent
    data class SetRailMode(val mode: RailMode) : MeEvent
    data class SetThemeMode(val mode: ThemeMode) : MeEvent
    data class MessageShown(val id: Long) : MeEvent

    data class OpenReminder(val id: String) : MeEvent
    data object OpenAmapKey : MeEvent
    data object OpenAbout : MeEvent
    data object OpenThemeSettings : MeEvent
}

/** 点了「导出上车判定记录」但还没有记录（或分享面板打不开）时的提示 */
const val NO_BOARDING_TRACE_MESSAGE = "还没有判定记录：开着「上车后自动结束追踪」追一次车之后再导出"

/** 到站前提醒能选的站数（和通知上「切换」按钮的 1 → 2 → 3 一致） */
val ALERT_THRESHOLD_CHOICES: List<Int> = listOf(1, 2, 3)

class MeViewModel(
    private val settings: SettingsRepository,
    private val reminderRepository: IScheduledReminderRepository,
    private val amapKeys: AmapKeyRepository,
    private val notices: MeNotices,
    private val liveUpdateAccess: LiveUpdateAccess? = null,
    private val versionName: String? = null,
    private val notificationHealthAccess: NotificationHealthAccess? = null,
    private val boardingTrace: BoardingTraceLog? = null
) : ViewModel() {

    private val liveUpdateStatus = MutableStateFlow(liveUpdateAccess?.status())

    private val notificationHealthStatus = MutableStateFlow(notificationHealthAccess?.status())

    /** 两个平台状态的合流：`combine` 的 5 参数重载已经用满，先把这两个并成一个 */
    private val platformStatus = combine(liveUpdateStatus, notificationHealthStatus) { live, health -> live to health }

    private val settingsPart = combine(
        settings.engineMode,
        settings.preferredTransitCodeProvider,
        settings.alertThresholdStops,
        settings.isLiveNotificationEnabled,
        settings.showOnLockScreen
    ) { engine, code, stops, live, lock -> SettingsPart(engine, code, stops, live, lock) }
        .combine(settings.showTransitCodeButton) { part, visible -> part.copy(showTransitCodeButton = visible) }
        .combine(settings.autoStopAfterBoarding) { part, enabled -> part.copy(autoStopAfterBoarding = enabled) }
        .combine(settings.voiceAlerts) { part, enabled -> part.copy(voiceAlerts = enabled) }
        .combine(settings.uiMode) { part, mode -> part.copy(elderMode = mode == UiMode.Elder) }
        .combine(settings.railMode) { part, mode -> part.copy(railMode = mode) }
        .combine(settings.themeMode) { part, mode -> part.copy(themeMode = mode) }

    private val mapKeyStatus = combine(amapKeys.hasMapKey, amapKeys.canShowMap) { has, canShow ->
        amapKeyStatusText(has, canShow)
    }

    val uiState: StateFlow<MeUiState> = combine(
        settingsPart,
        reminderRepository.reminders,
        platformStatus,
        mapKeyStatus,
        notices.messages
    ) { part, reminders, platform, keyStatus, messages ->
        buildState(part, reminders, platform.first, platform.second, keyStatus, messages)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = buildState(
            SettingsPart(
                settings.engineMode.value,
                settings.preferredTransitCodeProvider.value,
                settings.alertThresholdStops.value,
                settings.isLiveNotificationEnabled.value,
                settings.showOnLockScreen.value,
                settings.showTransitCodeButton.value,
                settings.uiMode.value == UiMode.Elder,
                settings.railMode.value,
                settings.autoStopAfterBoarding.value,
                settings.themeMode.value,
                settings.voiceAlerts.value
            ),
            reminderRepository.reminders.value,
            liveUpdateStatus.value,
            notificationHealthStatus.value,
            amapKeyStatusText(amapKeys.hasMapKey.value, amapKeys.canShowMap.value),
            notices.messages.value
        )
    )

    init {
        notificationHealthAccess?.changes?.let { changes ->
            viewModelScope.launch { changes.collect { notificationHealthStatus.value = it } }
        }
    }

    fun onEvent(event: MeEvent) {
        when (event) {
            is MeEvent.SetEngineMode -> settings.setEngineMode(event.mode)
            is MeEvent.SetTransitCode -> viewModelScope.launch {
                settings.setPreferredTransitCodeProvider(event.provider)
            }
            is MeEvent.SetAlertThresholdStops -> settings.setAlertThresholdStops(event.stops)
            is MeEvent.SetShowTransitCodeButton -> settings.setShowTransitCodeButton(event.visible)
            is MeEvent.SetAutoStopAfterBoarding -> settings.setAutoStopAfterBoarding(event.enabled)
            MeEvent.ExportBoardingTrace -> viewModelScope.launch {
                if (boardingTrace?.share() != true) notices.post(NO_BOARDING_TRACE_MESSAGE)
            }
            is MeEvent.SetVoiceAlerts -> settings.setVoiceAlerts(event.enabled)
            is MeEvent.ToggleReminder -> viewModelScope.launch {
                try {
                    reminderRepository.toggleReminder(event.id, event.enabled)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    notices.post(error.message ?: "提醒更新失败，请重试")
                }
            }
            is MeEvent.SetLiveNotificationEnabled -> settings.setLiveNotificationEnabled(event.enabled)
            is MeEvent.SetShowOnLockScreen -> settings.setShowOnLockScreen(event.enabled)
            MeEvent.OpenLiveUpdateSettings -> liveUpdateAccess?.openSettings()
            MeEvent.OpenAlertChannelSettings -> notificationHealthAccess?.openAlertChannelSettings()
            // 第 30 步只换主题；长辈版导航和页面在第 31–34 步
            is MeEvent.SetElderMode -> settings.setUiMode(if (event.enabled) UiMode.Elder else UiMode.Standard)
            is MeEvent.SetRailMode -> settings.setRailMode(event.mode)
            is MeEvent.SetThemeMode -> settings.setThemeMode(event.mode)
            is MeEvent.MessageShown -> notices.consume(event.id)
            // 导航事件由 Route 处理
            is MeEvent.OpenReminder, MeEvent.OpenAmapKey, MeEvent.OpenAbout, MeEvent.OpenThemeSettings -> Unit
        }
    }

    /** 回到前台时重查实时活动通知的权限和通知体检（用户可能刚从系统设置回来） */
    fun onScreenResumed() {
        liveUpdateStatus.value = liveUpdateAccess?.status()
        notificationHealthStatus.value = notificationHealthAccess?.status()
    }

    private fun buildState(
        part: SettingsPart,
        reminders: List<ScheduledReminder>,
        liveUpdate: LiveUpdateStatus?,
        notificationHealth: NotificationHealth?,
        keyStatus: String,
        messages: List<UserMessage>
    ) = MeUiState(
        engineMode = part.engineMode,
        preferredTransitCode = part.transitCode,
        showTransitCodeButton = part.showTransitCodeButton,
        autoStopAfterBoarding = part.autoStopAfterBoarding,
        canExportBoardingTrace = boardingTrace != null,
        voiceAlerts = part.voiceAlerts,
        alertThresholdStops = part.alertThresholdStops,
        reminders = reminders,
        remindersSubtitle = remindersRowSubtitle(reminders),
        isLiveNotificationEnabled = part.liveNotification,
        showOnLockScreen = part.showOnLockScreen,
        elderMode = part.elderMode,
        railMode = part.railMode,
        themeMode = part.themeMode,
        // 和原来一样：实时通知关着时不显示胶囊权限
        liveUpdate = liveUpdate?.takeIf { part.liveNotification },
        // 只在真有问题时出现，正常时那一行不该占地方
        notificationHealth = notificationHealth?.takeIf { !it.healthy },
        amapKeyStatus = keyStatus,
        versionName = versionName,
        userMessages = messages
    )

    private data class SettingsPart(
        val engineMode: EngineMode,
        val transitCode: TransitCodeProvider,
        val alertThresholdStops: Int,
        val liveNotification: Boolean,
        val showOnLockScreen: Boolean,
        val showTransitCodeButton: Boolean = true,
        val elderMode: Boolean = false,
        val railMode: RailMode = RailMode.Horizontal,
        val autoStopAfterBoarding: Boolean = true,
        val themeMode: ThemeMode = ThemeMode.System,
        val voiceAlerts: Boolean = false
    )
}
