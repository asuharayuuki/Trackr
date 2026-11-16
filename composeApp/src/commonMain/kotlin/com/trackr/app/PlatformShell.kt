package com.trackr.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.navigation.Navigator
import com.trackr.core.navigation.ScreenKey

/** 包在 NavDisplay 外面的平台部分。Android：Activity 级的高德地图会话（切页签不销毁地图，§3.4） */
@Composable
internal expect fun PlatformHost(enabled: Boolean, content: @Composable () -> Unit)

/**
 * 首次打开时的定位 / 通知权限说明与申请。Android：先弹说明再申请定位和通知；
 * iOS：没有说明对话框，直接申请使用期间定位（通知授权在第一次追踪 / 存提醒时才申请）。
 * 说明对话框和系统权限弹窗都关掉后才组合 [afterOnboarding]，后面的对话框（字号提示）不会叠在它们上面。
 */
@Composable
internal expect fun PermissionOnboarding(afterOnboarding: @Composable () -> Unit)

/** 底栏图标。Android 照旧用 Material 图标；iOS 用设计系统的 Compose 资源 */
@Composable
internal expect fun bottomTabIcon(key: ScreenKey): ImageVector

