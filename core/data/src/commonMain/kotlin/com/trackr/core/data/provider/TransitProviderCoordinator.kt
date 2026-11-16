package com.trackr.core.data.provider

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.model.isPureSubwayStation
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.mygolbs.MygolbsIds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 数据源模式对应的数据源 */
val EngineMode.dataSource: DataSourceType
    get() = when (this) {
        EngineMode.CHELAILE -> DataSourceType.CHELAILE
        EngineMode.MYGOLBS -> DataSourceType.MYGOLBS
        EngineMode.LENZ -> DataSourceType.LENZ
    }

/**
 * 实时公交数据源分发协调器（D1：数据源二选一，不做聚合）
 *
 * 搜索、附近站、站点详情按当前数据源（[EngineMode]）选一个 Provider；线路详情、时刻表按线路自带的来源或 id 前缀选，
 * 认不出来才用当前数据源。城市列表仍合并两家（掌上公交编码要补到车来了城市上）。
 */
class TransitProviderCoordinator(
    val providers: Map<DataSourceType, ITransitProvider>
) {
    constructor(
        chelaileProvider: ITransitProvider,
        mygolbsProvider: ITransitProvider,
        lenzProvider: ITransitProvider? = null
    ) : this(
        buildMap {
            put(DataSourceType.CHELAILE, chelaileProvider)
            put(DataSourceType.MYGOLBS, mygolbsProvider)
            if (lenzProvider != null) {
                put(DataSourceType.LENZ, lenzProvider)
            }
        }
    )

    private var cachedCities: List<TransitCity> = emptyList()

    /** 某个数据源对应的 Provider；认不出来返回 null */
    fun providerFor(source: DataSourceType): ITransitProvider? = providers[source]

    /**
     * 并发获取车来了与掌上公交支持的城市列表，并进行编码与独有城市双源融合
     */
    suspend fun getSupportedCities(forceRefresh: Boolean = false): List<TransitCity> {
        if (!forceRefresh && cachedCities.isNotEmpty()) {
            return cachedCities
        }

        val chelaileProvider = providers[DataSourceType.CHELAILE]
        val mygolbsProvider = providers[DataSourceType.MYGOLBS]
        val lenzProvider = providers[DataSourceType.LENZ]

        val (chelaileCities, mygolbsCities, lenzCities) = coroutineScope {
            val clDeferred = async {
                runCatching { chelaileProvider?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())
            }
            val mgDeferred = async {
                runCatching { mygolbsProvider?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())
            }
            val lzDeferred = async {
                runCatching { lenzProvider?.getSupportedCities().orEmpty() }.getOrDefault(emptyList())
            }
            Triple(clDeferred.await(), mgDeferred.await(), lzDeferred.await())
        }

        // 构建掌上公交映射 cleanName -> TransitCity
        val mgMap = LinkedHashMap<String, TransitCity>()
        for (mgCity in mygolbsCities) {
            val cleanName = ProvinceCityCatalog.cleanCityName(mgCity.cityName)
            if (cleanName.isNotBlank() && !mgMap.containsKey(cleanName)) {
                mgMap[cleanName] = mgCity
            }
        }

        // 构建朗斯掌上出行映射 cityName -> TransitCity
        // 不用 cleanCityName：「范县」会被削成「范」，县级市的名字要原样保留
        val lzMap = LinkedHashMap<String, TransitCity>()
        for (lzCity in lenzCities) {
            val key = lzCity.cityName.trim()
            if (key.isNotBlank() && !lzMap.containsKey(key)) {
                lzMap[key] = lzCity
            }
        }

        val matchedMgKeys = HashSet<String>()
        val matchedLzKeys = HashSet<String>()
        val result = ArrayList<TransitCity>()

        // 1. 以车来了城市为主干进行聚合去重，统一采用前者简洁名称（如“上海”而非“上海市”）
        for (cCity in chelaileCities) {
            val rawName = cCity.cityName.trim()
            val cleanName = ProvinceCityCatalog.cleanCityName(rawName)
            val mgMatch = mgMap[cleanName]
            // 县级名会被 cleanCityName 削掉「县」字（滑县→滑），先用原名匹配，再退回简名
            val lzMatch = lzMap[rawName] ?: lzMap[cleanName]
            val effectiveProv = if (cCity.province.isNotBlank()) {
                cCity.province
            } else {
                ProvinceCityCatalog.inferProvince(cCity.cityName)
            }
            if (mgMatch != null) {
                matchedMgKeys.add(cleanName)
            }
            if (lzMatch != null) {
                matchedLzKeys.add(lzMatch.cityName)
            }
            val displayName = lzMatch?.cityName ?: cleanName.ifBlank { cCity.cityName }
            result.add(
                cCity.copy(
                    cityName = displayName,
                    province = effectiveProv,
                    mygolbsCityCode = mgMatch?.mygolbsCityCode,
                    lenzServerIp = lzMatch?.lenzServerIp,
                    lenzServerPort = lzMatch?.lenzServerPort,
                    lenzAreaCode = lzMatch?.lenzAreaCode,
                    lenzRegion = lzMatch?.lenzRegion
                )
            )
        }

        // 2. 掌上公交独有的城市列表追加合入（只留简洁名称）
        for ((cleanName, mgCity) in mgMap) {
            if (!matchedMgKeys.contains(cleanName)) {
                val rawName = mgCity.cityName.trim()
                val lzMatch = lzMap[rawName] ?: lzMap[cleanName]
                if (lzMatch != null) {
                    matchedLzKeys.add(lzMatch.cityName)
                }
                val effectiveProv = if (mgCity.province.isNotBlank()) {
                    mgCity.province
                } else {
                    ProvinceCityCatalog.inferProvince(mgCity.cityName)
                }
                val displayName = lzMatch?.cityName ?: cleanName.ifBlank { mgCity.cityName }
                result.add(
                    mgCity.copy(
                        cityId = "",
                        cityName = displayName,
                        province = effectiveProv,
                        lenzServerIp = lzMatch?.lenzServerIp,
                        lenzServerPort = lzMatch?.lenzServerPort,
                        lenzAreaCode = lzMatch?.lenzAreaCode,
                        lenzRegion = lzMatch?.lenzRegion
                    )
                )
            }
        }

        // 3. 朗斯掌上出行独有的城市列表追加合入
        for ((lzKey, lzCity) in lzMap) {
            if (!matchedLzKeys.contains(lzKey)) {
                val effectiveProv = if (lzCity.province.isNotBlank()) {
                    lzCity.province
                } else {
                    ProvinceCityCatalog.inferProvince(lzCity.cityName)
                }
                // 县级市保留「县」字（cleanCityName 会把「范县」削成「范」，这里不用它）
                val displayName = lzCity.cityName
                result.add(
                    lzCity.copy(
                        cityId = "",
                        cityName = displayName,
                        province = effectiveProv
                    )
                )
            }
        }

        cachedCities = result
        return result
    }

    /**
     * 当前数据源列出的城市全部线路；不支持列全部的源返回空表（首页退回搜索）。
     */
    suspend fun listAllLines(
        city: TransitCity,
        mode: EngineMode = EngineMode.CHELAILE
    ): List<BusLineSummary> {
        val provider = providers[mode.dataSource]
        val primary = provider?.let { runCatching { it.listAllLines(city) }.getOrNull() }.orEmpty()
        if (primary.isNotEmpty() || mode == EngineMode.LENZ) return primary

        // 当前源列不出全部（车来了 / 掌上公交都不支持），县级城市再问问掌上出行
        val lenz = providers[DataSourceType.LENZ] ?: return primary
        return runCatching { lenz.listAllLines(city) }.getOrNull().orEmpty()
    }

    /**
     * 查出这批城市里「数据源连不上」的城市名。
     *
     * 只对掌上出行有意义（每个城市一台服务器）；车来了、掌上公交走统一公网入口，
     * 这里永远返回空集。供城市选择器把离线城市标出来，免得用户选了之后一头雾水。
     */
    suspend fun unreachableCities(cities: List<TransitCity>): Set<String> {
        val lenz = providers[DataSourceType.LENZ] ?: return emptySet()
        val candidates = cities.filter { !it.lenzServerIp.isNullOrBlank() }.take(24)
        if (candidates.isEmpty()) return emptySet()
        return coroutineScope {
            candidates.map { city ->
                async { city.cityName to runCatching { lenz.isCityReachable(city) }.getOrDefault(true) }
            }.map { it.await() }
        }.filterNot { it.second }.map { it.first }.toSet()
    }

    /** 逆地理先问车来了、再问掌上公交（和数据源选哪个无关，只用来定城市） */
    private val geocodeProviders: List<ITransitProvider>
        get() = GEOCODE_ORDER.mapNotNull { providers[it] } +
                providers.values.filter { it.sourceType !in GEOCODE_ORDER }

    /**
     * 逆地理定位：依次查询具备雷达能力的 Provider，返回第一个非空城市名
     */
    suspend fun resolveCityByLocation(
        wgsLat: Double,
        wgsLng: Double
    ): String? {
        for (provider in geocodeProviders) {
            if (provider.supportsLocationRadar()) {
                val resolved = runCatching { provider.resolveCityByLocation(wgsLat, wgsLng) }.getOrNull()
                if (!resolved.isNullOrBlank()) {
                    return resolved
                }
            }
        }
        return null
    }

    /**
     * 逆地理区县：依次查询，返回第一个非空的区县名（未清洗）
     */
    suspend fun resolveDistrictByLocation(
        wgsLat: Double,
        wgsLng: Double
    ): String? {
        for (provider in geocodeProviders) {
            if (provider.supportsLocationRadar()) {
                val resolved = runCatching { provider.resolveDistrictByLocation(wgsLat, wgsLng) }.getOrNull()
                if (!resolved.isNullOrBlank()) {
                    return resolved
                }
            }
        }
        return null
    }

    /**
     * 获取周边站点：根据 EngineMode 单源路由至支持位置雷达能力的 Provider。
     *
     * 掌上出行没有可用的「附近站」接口（命令 91 在各城市服务器上都不返回数据），
     * 它返回空时退回车来了，避免用户切到掌上出行后首页「附近站点」整块变空。
     */
    suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4,
        mode: EngineMode = EngineMode.CHELAILE
    ): List<NearbyStop> {
        val targetProvider = providers[mode.dataSource]

        val primary = if (targetProvider != null && targetProvider.supportsLocationRadar()) {
            runCatching { targetProvider.getNearbyStops(city, wgsLat, wgsLng, limit) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (primary.isNotEmpty() || mode != EngineMode.LENZ) return primary

        val chelaile = providers[DataSourceType.CHELAILE] ?: return primary
        return runCatching { chelaile.getNearbyStops(city, wgsLat, wgsLng, limit) }.getOrDefault(emptyList())
    }

    /**
     * 搜索：只查当前数据源，去掉地铁线路和纯地铁站，线路按名字自然排序。
     *
     * 当前数据源一条都没搜到时，再问一次朗斯「掌上出行」：朗斯覆盖大量县级公交
     * （如河南濮阳下辖的范县 / 清丰 / 台前），这些城市在车来了、掌上公交里都没有，
     * 用户把城市设成地级市「濮阳」时，只有朗斯能搜到本县线路。
     * 朗斯没有该城市服务器时这一步不发任何网络请求，因此不影响常规搜索速度。
     */
    suspend fun search(
        city: TransitCity,
        keyword: String,
        mode: EngineMode,
        userLat: Double? = null,
        userLng: Double? = null
    ): TransitSearchResult {
        var raw = providers[mode.dataSource]?.let {
            runCatching { it.search(city, keyword, userLat, userLng) }.getOrNull()
        }
        if (raw == null || raw.lines.isEmpty()) {
            val lenz = providers[DataSourceType.LENZ]
            if (lenz != null && mode != EngineMode.LENZ) {
                val fallbackLines = runCatching { lenz.search(city, keyword, userLat, userLng) }
                    .getOrNull()?.lines.orEmpty()
                if (fallbackLines.isNotEmpty()) {
                    raw = TransitSearchResult(
                        keyword = keyword,
                        lines = fallbackLines,
                        stops = raw?.stops.orEmpty()
                    )
                }
            }
        }
        // 第三方源没有地铁的在途实时数据，地铁线路和纯地铁站不展示
        val lines = raw?.lines.orEmpty()
            .filterNot { it.isSubway || it.name.contains("地铁") }
            .sortedWith { a, b -> RouteProjectionMath.compareLineNames(a.name, b.name) }
        val stops = raw?.stops.orEmpty().filterNot { it.isPureSubwayStation }
        return TransitSearchResult(keyword = keyword, lines = lines, stops = stops)
    }

    /**
     * 站点详情：根据 EngineMode 或请求显式特征精准单源路由
     */
    suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest,
        mode: EngineMode
    ): List<NearbyStop> {
        val chelaileProvider = providers[DataSourceType.CHELAILE]
        val mygolbsProvider = providers[DataSourceType.MYGOLBS]

        // 若请求明确包含掌上公交前缀特征，或当前引擎模式为掌上公交，路由至掌上公交
        val isExplicitMygolbs = when {
            mygolbsProvider?.supportsStop(request) == true -> true
            chelaileProvider?.supportsStop(request) == false -> true
            else -> mode == EngineMode.MYGOLBS
        }

        return if (isExplicitMygolbs) {
            mygolbsProvider?.getStopDetail(city, request).orEmpty()
        } else {
            chelaileProvider?.getStopDetail(city, request).orEmpty()
        }
    }

    /** 认不出线路来源时用的 Provider：当前数据源，没有就随便一个 */
    private fun fallbackProvider(mode: EngineMode): ITransitProvider? =
        providers[mode.dataSource] ?: providers.values.firstOrNull()

    /**
     * 线路详情：根据请求显式 source 或 lineId 前缀特征精准路由，认不出来走当前数据源
     */
    suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest,
        mode: EngineMode = EngineMode.CHELAILE
    ): BusLineDetail {
        val defaultFallback = fallbackProvider(mode)

        val targetProvider = when {
            request.source != null -> providers[request.source]
            providers[DataSourceType.LENZ]?.supportsLine(request.lineId) == true -> providers[DataSourceType.LENZ]
            providers[DataSourceType.MYGOLBS]?.supportsLine(request.lineId) == true -> providers[DataSourceType.MYGOLBS]
            providers[DataSourceType.CHELAILE]?.supportsLine(request.lineId) == true -> providers[DataSourceType.CHELAILE]
            else -> providers.values.firstOrNull { it.supportsLine(request.lineId) } ?: defaultFallback
        } ?: throw IllegalStateException("No provider found for lineId: ${request.lineId}")

        return targetProvider.getLineDetail(city, request)
    }

    /**
     * 运营时刻表：根据线路显式 source 或 lineId 前缀特征精准路由，认不出来走当前数据源
     */
    suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String? = null,
        mode: EngineMode = EngineMode.CHELAILE
    ): LineTimetable {
        val defaultFallback = fallbackProvider(mode)

        val targetProvider = when {
            line.source == DataSourceType.MYGOLBS || MygolbsIds.isMygolbsId(line.lineId) -> providers[DataSourceType.MYGOLBS]
            line.source == DataSourceType.CHELAILE -> providers[DataSourceType.CHELAILE]
            providers[DataSourceType.MYGOLBS]?.supportsLine(line.lineId) == true -> providers[DataSourceType.MYGOLBS]
            providers[DataSourceType.CHELAILE]?.supportsLine(line.lineId) == true -> providers[DataSourceType.CHELAILE]
            else -> defaultFallback
        } ?: throw IllegalStateException("No provider found for timetable: ${line.lineId}")

        return targetProvider.getTimetable(city, line, atTimeHHMM)
    }

    private companion object {
        val GEOCODE_ORDER = listOf(DataSourceType.CHELAILE, DataSourceType.MYGOLBS)
    }
}
