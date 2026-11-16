package com.trackr.feature.route

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.ui.screens.routedetail.RouteDetailViewModel
import com.trackr.ui.screens.routeplan.RoutePlanViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 路线规划页签和路线详情的导航入口（`:composeApp` 组装）。
 * 两个页面都在 commonMain；entry 还是 expect / actual，因为 Android 的 actual 文件里另带高德 SDK 的 Koin 模块
 * `routeAndroidModule`，两边的 entry 内容目前相同。
 */
expect fun EntryProviderScope<NavKey>.routeEntries(navigator: TrackrNavigator)

/**
 * 规划页、路线（追踪 / 实时到站）的 Koin 定义：原 `FavoritesGroupViewModel` 拆出来的两个。
 * 两个都取 Activity 级的一个实例（规划页和路线详情页共用 [RouteDetailViewModel]，追踪状态两边一致）。
 */
val routeModule = module {
    viewModel {
        RoutePlanViewModel(
            routePlan = get(),
            settings = get(),
            realtime = get(),
            userLocationStore = get(),
            groupRepository = get(),
            groupSaver = get(),
            amapKeys = get(),
            locationRepository = get()
        )
    }
    viewModel {
        RouteDetailViewModel(
            groupRepository = get(),
            settings = get(),
            realtime = get(),
            alignment = get(),
            trackingRepository = get(),
            trackingController = get()
        )
    }
}
