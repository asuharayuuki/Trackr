package com.trackr.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 旧名别名：v3 之前的两套语义色（BusSemanticColors、ExtendedColors）。
// 字段名不变，值全部指向 v3 色（TrackrColors + ColorScheme）。
// 线路页（走向条、主卡、车辆列表）、规划页和 :core:ui 的 BusArrivalBadge / LegLineColor 还在读这些旧名，所以本文件还不能删；
// 新代码读 TrackrTheme.colors / MaterialTheme.colorScheme。

/** 旧名：公交语义色。每个字段指向的 v3 色见 [legacyBusColors]。 */
@Immutable
data class BusSemanticColors(
    val busRunningGreen: Color,
    val busArrivingAmber: Color,
    val busPassedGray: Color,
    val routeTrackActive: Color,
    val routeTrackInactive: Color,
    val conditionStopBorder: Color,
    val busChipContainer: Color,
    val busChipContent: Color,
    val stationCardContainer: Color,
    val alertAccent: Color,
)

/** 旧名：扩展语义色。每个字段指向的 v3 色见 [legacyExtendedColors]。 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val legSecondary: Color,
    val walk: Color,
)

/** v3：车快到 / 已到都用绿；已过站用灰；轨道用品牌蓝；条件停靠与预警用橙。 */
internal fun legacyBusColors(colors: TrackrColors, scheme: ColorScheme) = BusSemanticColors(
    busRunningGreen = colors.go,
    busArrivingAmber = colors.go,
    busPassedGray = colors.walk,
    routeTrackActive = scheme.primary,
    routeTrackInactive = scheme.outline,
    conditionStopBorder = colors.warn,
    busChipContainer = colors.goContainer,
    busChipContent = colors.go,
    stationCardContainer = scheme.surface,
    alertAccent = colors.warn,
)

/** v3：不同程不再用不同颜色，第 2 程也用品牌蓝。 */
internal fun legacyExtendedColors(colors: TrackrColors, scheme: ColorScheme) = ExtendedColors(
    success = colors.go,
    successContainer = colors.goContainer,
    onSuccessContainer = colors.go,
    warning = colors.warn,
    warningContainer = colors.warnContainer,
    legSecondary = scheme.primary,
    walk = colors.walk,
)

val LightBusSemanticColors = legacyBusColors(LightTrackrColors, LightColorScheme)
val DarkBusSemanticColors = legacyBusColors(DarkTrackrColors, DarkColorScheme)
val LightExtendedColors = legacyExtendedColors(LightTrackrColors, LightColorScheme)
val DarkExtendedColors = legacyExtendedColors(DarkTrackrColors, DarkColorScheme)

val LocalBusSemanticColors = staticCompositionLocalOf { LightBusSemanticColors }

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** 旧名：当前主题的公交语义色。 */
val MaterialTheme.busColors: BusSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBusSemanticColors.current

/** 旧名：当前主题的扩展语义色。 */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current
