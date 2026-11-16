package com.trackr.data.repository

import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SearchHistoryRepository
import com.trackr.core.data.repository.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一实时公交数据仓库契约（只在 `:androidApp` 里用）。
 *
 * 第 8 步把原来的上帝接口拆成 `:core:data` 里的 [SettingsRepository]、[FavoritesRepository]、
 * [RealtimeRepository]、[SearchHistoryRepository]（定位另有 `LocationRepository`），这里只是把它们合在一起。
 * 功能模块的 ViewModel 只拿要用的小接口；Koin 把这些小接口都绑到同一个 `TransitRepository` 实例上，
 * `:androidApp` 自己的追踪服务、提醒接收器、小组件同步直接拿这个合并接口。
 */
interface ITransitRepository : SettingsRepository, FavoritesRepository, RealtimeRepository, SearchHistoryRepository {

    /**
     * 首页定位写入的最近坐标（WGS-84），站点详情 / 搜索 / 规划页「我的位置」没传坐标时用。
     * 功能模块经 `:core:data` 的 `UserLocationStore` 读写（`PlatformBridgeModule` 里适配到这两个成员）。
     */
    val userLocation: StateFlow<Pair<Double, Double>?>

    fun updateUserLocation(lat: Double, lng: Double)
}
