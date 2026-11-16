package com.trackr.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModelStoreOwner

/**
 * 根组合（Activity）的 [ViewModelStoreOwner]，由 `TrackrRoot` 在 `NavDisplay` 外面提供。
 *
 * 跨页共享同一个实例的 ViewModel 取 Activity 级（`koinViewModel(viewModelStoreOwner = …)`）：
 * 首页（`BusHomeViewModel`，标准版 / 长辈版首页和权限回调共用）、规划页和路线详情
 * （`RoutePlanViewModel`、`RouteDetailViewModel`）。其余页面的 ViewModel 是 entry 级。
 */
val LocalAppViewModelStoreOwner = staticCompositionLocalOf<ViewModelStoreOwner> {
    error("LocalAppViewModelStoreOwner 没有提供：要在 TrackrRoot 里用")
}
