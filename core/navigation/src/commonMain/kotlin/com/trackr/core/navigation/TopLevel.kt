package com.trackr.core.navigation

import androidx.navigation3.runtime.NavKey

/**
 * 顶层页签（每个页签一条返回栈），3 个：公交、路线、我的。
 * Key 沿用旧名（[ScreenKey.Home] / [ScreenKey.RoutePlan] / [ScreenKey.More]），底栏文字在 `:composeApp` 的 `TrackrRoot` 里。
 * 「收藏」不是页签：编辑收藏是首页栈的子页（[ScreenKey.FavoritesEdit]）；[ScreenKey.FavoritesGroup] 的页面已删，只剩 Key。
 */
object TopLevel {
    /** 起始页签：非起始页签在根页按返回回到这里；它的根页再按返回就退出（exit-through-home）。 */
    val start: ScreenKey = ScreenKey.Home

    /** 底栏顺序。 */
    val tabs: List<ScreenKey> = listOf(
        ScreenKey.Home,
        ScreenKey.RoutePlan,
        ScreenKey.More,
    )

    fun isTopLevel(key: NavKey?): Boolean = key != null && key in tabs

    /**
     * 底栏实际显示的页签（D2）：没有高德 Key（`AmapKeyRepository.hasMapKey` 为 false）时去掉「路线规划」。
     * 顺序同 [tabs]。页签集合变了交给 [Navigator.onTabsChanged]（在被去掉的页签上就清栈回起始页签）。
     */
    fun visibleTabs(hasMapKey: Boolean): List<ScreenKey> =
        if (hasMapKey) tabs else tabs.filterNot { it == ScreenKey.RoutePlan }
}
