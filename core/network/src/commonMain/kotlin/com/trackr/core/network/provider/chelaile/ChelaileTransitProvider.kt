package com.trackr.core.network.provider.chelaile

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.model.isPureSubwayStation
import com.trackr.core.network.provider.BaseTransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.chelaile.ChelaileApiClient
import com.trackr.core.network.remote.common.SingleFlightCache
import com.trackr.core.network.remote.mygolbs.MygolbsIds
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 车来了（Chelaile）数据源 Provider
 * 内聚加密请求、多物理站台并发补全、同源经纬度就近兜底与纯地铁过滤
 */
class ChelaileTransitProvider(
    private val chelaileClient: ChelaileApiClient,
    /** 单调时钟：各级补全缓存的过期判断；测试可注入 */
    private val clock: MonotonicClock = ProcessMonotonicClock
) : BaseTransitProvider() {

    override val sourceType: DataSourceType = DataSourceType.CHELAILE

    /** 附近站实时补全（encryptedPhyStnDetail）短缓存：同一站台 20 秒内复用，首页轮询不再每轮补全 */
    private val nearbyCompletionCache = SingleFlightCache<String, List<NearbyStop>>(
        ttlMillis = NEARBY_COMPLETION_TTL_MS,
        maxEntries = 64,
        clock = clock
    )

    /** 站点详情中其他物理站台的补全缓存：只在首次（或缓存过期后）补全一次 */
    private val platformCompletionCache = SingleFlightCache<String, List<NearbyStop>>(
        ttlMillis = PLATFORM_COMPLETION_TTL_MS,
        maxEntries = 64,
        clock = clock
    )

    /** 补全请求并发上限，避免附近站数量调大时同时打出十几个请求 */
    private val completionSemaphore = Semaphore(MAX_CONCURRENT_COMPLETIONS)

    /** 主查询返回空结果的站点 key -> 记录时刻；有效期内直接走经纬度兜底，不再重复主查询 */
    private val emptyMainQueryAt = LockedLongMap()

    /** 车来了接住所有不是掌上公交协议的 id（旧行为） */
    override fun ownsId(id: String): Boolean = !MygolbsIds.isMygolbsId(id)

    /** 一个 id 都没给时按旧行为算车来了 */
    override val missingIdMeansOwned: Boolean = true

    override suspend fun loadSupportedCities(): List<TransitCity> =
        chelaileClient.getCities(hotOnly = false)

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? {
        return chelaileClient.reverseGeocodeCityName(wgsLat, wgsLng)
    }

    override suspend fun resolveDistrictByLocation(wgsLat: Double, wgsLng: Double): String? {
        return chelaileClient.reverseGeocodeDistrictName(wgsLat, wgsLng)
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): TransitSearchResult {
        return chelaileClient.search(
            cityId = city.cityId,
            keyword = keyword,
            userLat = userLat,
            userLng = userLng
        )
    }

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): List<NearbyStop> {
        val rawStops = chelaileClient.getNearbyStops(
            cityId = city.cityId,
            wgsLat = wgsLat,
            wgsLng = wgsLng,
            limit = limit
        ).filter { !it.isPureSubwayStation }

        // 车来了 encryptedHomePage 接口只对前 2 个站点填充实时到站数据（hasBusInfo: true），
        // 从第 3 个站点开始 lines 中的实时车辆为空且 shortDesc 为空。
        // 对缺少实时数据的站点，在 coroutineScope 中并发调用 getStopDetail 补全实时数据。
        val needsCompletion = rawStops.any { stop ->
            stop.lines.isNotEmpty() &&
                    stop.lines.all { it.approachingBuses.isEmpty() && it.statusText.isBlank() && it.preArrivalTime.isNullOrBlank() } &&
                    (!stop.physicalStId.isNullOrBlank() || stop.stopId.isNotBlank())
        }

        if (!needsCompletion) {
            return rawStops
        }

        return coroutineScope {
            rawStops.map { stop ->
                async {
                    val needsFetch = stop.lines.isNotEmpty() &&
                            stop.lines.all { it.approachingBuses.isEmpty() && it.statusText.isBlank() && it.preArrivalTime.isNullOrBlank() } &&
                            (!stop.physicalStId.isNullOrBlank() || stop.stopId.isNotBlank())

                    if (needsFetch) {
                        // 补全只取线路与实时状态，与用户坐标无关，可按站台复用
                        val cacheKey = "${city.cityId}|${stop.physicalStId.orEmpty()}|${stop.stopId}"
                        val subResult = runCatching {
                            nearbyCompletionCache.get(cacheKey) {
                                completionSemaphore.withPermit {
                                    chelaileClient.getStopDetail(
                                        cityId = city.cityId,
                                        physicalStId = stop.physicalStId.orEmpty(),
                                        namesakeStId = stop.namesakeStId,
                                        stationId = stop.stopId,
                                        userLat = wgsLat,
                                        userLng = wgsLng
                                    )
                                }
                            }
                        }.getOrNull()

                        val matched = subResult?.firstOrNull {
                            (!stop.physicalStId.isNullOrBlank() && it.physicalStId == stop.physicalStId && it.lines.any { l -> l.line.lineId.isNotBlank() }) ||
                                    (stop.stopId.isNotBlank() && it.stopId == stop.stopId && it.lines.any { l -> l.line.lineId.isNotBlank() })
                        } ?: subResult?.firstOrNull { it.lines.any { l -> l.line.lineId.isNotBlank() } }

                        if (matched != null && matched.lines.isNotEmpty()) {
                            stop.copy(
                                lines = matched.lines,
                                subwayLineNames = if (matched.subwayLineNames.isNotEmpty()) matched.subwayLineNames else stop.subwayLineNames
                            )
                        } else stop
                    } else stop
                }
            }.awaitAll()
        }
    }

    override suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop> {
        val stationLat = request.stationLat
        val stationLng = request.stationLng
        val hasFallbackCoords = stationLat != null && stationLng != null && stationLat > 0.0 && stationLng > 0.0
        val mainQueryKey = "${city.cityId}|${request.physicalStId.orEmpty()}|${request.namesakeStId.orEmpty()}|${request.stopId.orEmpty()}"

        // 阶段 1：主查询（近期已确认主查询为空且有经纬度兜底时跳过，直接走兜底）
        val skipMainQuery = hasFallbackCoords && emptyMainQueryAt[mainQueryKey]?.let { at ->
            clock.elapsedMillis() - at in 0 until EMPTY_MAIN_QUERY_TTL_MS
        } == true
        val rawStops = if (skipMainQuery) {
            emptyList()
        } else {
            chelaileClient.getStopDetail(
                cityId = city.cityId,
                physicalStId = request.physicalStId.orEmpty(),
                namesakeStId = request.namesakeStId,
                stationId = request.stopId,
                userLat = request.userLat,
                userLng = request.userLng
            )
        }

        // 车来了接口只为当前查询的 physicalStId 返回完整线路与实时数据，其他物理站台为占位空壳
        // 当存在多个物理站台且其他站台尚未填充完整线路时，并发补全各物理站台的完整线路信息
        val stops = if (rawStops.size > 1 && rawStops.any { s -> s.lines.isNotEmpty() && s.lines.all { it.line.lineId.isBlank() } }) {
            coroutineScope {
                rawStops.map { stop ->
                    async {
                        val needsFetch = stop.lines.isNotEmpty() && stop.lines.all { it.line.lineId.isBlank() }
                        val physicalStId = stop.physicalStId
                        if (needsFetch && !physicalStId.isNullOrBlank()) {
                            // 其他物理站台的补全按站台缓存，3 秒轮询时不再每轮重复补全；
                            // 用户切到该站台后，页面会以它为主查询拿到最新实时数据
                            val cacheKey = "${city.cityId}|${stop.physicalStId}"
                            val subResult = runCatching {
                                platformCompletionCache.get(cacheKey) {
                                    completionSemaphore.withPermit {
                                        chelaileClient.getStopDetail(
                                            cityId = city.cityId,
                                            physicalStId = physicalStId,
                                            namesakeStId = stop.namesakeStId ?: request.namesakeStId,
                                            stationId = stop.stopId.takeIf { it.isNotBlank() } ?: request.stopId,
                                            userLat = request.userLat,
                                            userLng = request.userLng
                                        )
                                    }
                                }
                            }.getOrNull()
                            val matched = subResult?.firstOrNull { it.physicalStId == stop.physicalStId && it.lines.any { l -> l.line.lineId.isNotBlank() } }
                                ?: subResult?.firstOrNull { it.lines.any { l -> l.line.lineId.isNotBlank() } }
                            if (matched != null) {
                                stop.copy(
                                    lines = matched.lines,
                                    subwayLineNames = if (matched.subwayLineNames.isNotEmpty()) matched.subwayLineNames else stop.subwayLineNames
                                )
                            } else stop
                        } else stop
                    }
                }.awaitAll()
            }
        } else {
            rawStops
        }

        val filteredStops = stops.filter { !it.isPureSubwayStation }
        if (filteredStops.isNotEmpty()) {
            emptyMainQueryAt.remove(mainQueryKey)
            return filteredStops
        }

        // 阶段 2：同源经纬度就近兜底与距离计算
        if (stationLat != null && stationLng != null && hasFallbackCoords) {
            if (!skipMainQuery) {
                emptyMainQueryAt[mainQueryKey] = clock.elapsedMillis()
            }
            val nearby = chelaileClient.getNearbyStops(
                cityId = city.cityId,
                wgsLat = stationLat,
                wgsLng = stationLng,
                limit = 6
            ).filter { !it.isPureSubwayStation }

            val matched = if (!request.stopName.isNullOrBlank()) {
                val filtered = nearby.filter { it.name.contains(request.stopName) || request.stopName.contains(it.name) }
                if (filtered.isNotEmpty()) filtered else nearby
            } else {
                nearby
            }

            val userLat = request.userLat
            val userLng = request.userLng
            return if (userLat != null && userLng != null) {
                matched.map { s ->
                    if ((s.distanceMeters ?: 0) <= 0) {
                        val d = RouteProjectionMath.calculateDistanceMeters(userLat, userLng, s.wgsLat, s.wgsLng)
                        s.copy(distanceMeters = d)
                    } else s
                }
            } else {
                matched
            }
        }

        return emptyList()
    }

    override suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail {
        return chelaileClient.getLineDetail(
            cityId = city.cityId,
            lineId = request.lineId,
            targetOrder = request.targetOrder,
            stationId = request.stationId,
            wgsLat = request.wgsLat,
            wgsLng = request.wgsLng
        )
    }

    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): LineTimetable {
        val lineNo = line.lineNo.ifBlank { line.name }
        return chelaileClient.getTimetable(
            cityId = city.cityId,
            lineId = line.lineId,
            lineNo = lineNo,
            direction = line.direction,
            atTimeHHMM = atTimeHHMM
        )
    }

    companion object {
        const val NEARBY_COMPLETION_TTL_MS = 20_000L
        const val PLATFORM_COMPLETION_TTL_MS = 120_000L
        const val EMPTY_MAIN_QUERY_TTL_MS = 180_000L
        const val MAX_CONCURRENT_COMPLETIONS = 4
    }
}

/** 加锁的 String -> Long 表（代替 ConcurrentHashMap，commonMain 可用） */
private class LockedLongMap {
    private val lock = SynchronizedObject()
    private val map = HashMap<String, Long>()

    operator fun get(key: String): Long? = synchronized(lock) { map[key] }

    operator fun set(key: String, value: Long) {
        synchronized(lock) { map[key] = value }
    }

    fun remove(key: String) {
        synchronized(lock) { map.remove(key) }
    }
}
