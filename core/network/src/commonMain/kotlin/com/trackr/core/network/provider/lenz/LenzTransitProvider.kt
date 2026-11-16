package com.trackr.core.network.provider.lenz

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TimetableSegment
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.BaseTransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.network.remote.lenz.LenzApiClient
import com.trackr.core.network.remote.lenz.LenzIds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 朗斯通信「掌上出行」数据源 Provider
 */
class LenzTransitProvider(
    private val lenzClient: LenzApiClient
) : BaseTransitProvider() {

    override val sourceType: DataSourceType = DataSourceType.LENZ

    override fun ownsId(id: String): Boolean = LenzIds.isLenzId(id)

    override fun stopDetailIncludesBuses(): Boolean = true

    /** 掌上出行每城一台服务器，真连一次才知道能不能用 */
    override suspend fun isCityReachable(city: TransitCity): Boolean =
        lenzClient.isServerReachable(city)

    override suspend fun loadSupportedCities(): List<TransitCity> {
        return lenzClient.getCities()
    }

    override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? {
        return null
    }

    override suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double?,
        userLng: Double?
    ): TransitSearchResult {
        if (keyword.isBlank()) {
            return TransitSearchResult(keyword, emptyList(), emptyList())
        }
        return TransitSearchResult(keyword, queryLines(city, keyword), emptyList())
    }

    /** 对这台城市（或该地级市下所有县级服务器）发一次线路查询 */
    private suspend fun queryLines(city: TransitCity, keyword: String): List<BusLineSummary> {
        // 地级市（如濮阳）没有独立服务器时，联查其下属县级服务器（范县 / 清丰 / 台前…）
        val servers = lenzClient.citiesForSearch(city)
        if (servers.isEmpty()) return emptyList()

        val merged = coroutineScope {
            servers.map { server ->
                async {
                    val ip = server.lenzServerIp.orEmpty()
                    val port = server.lenzServerPort ?: return@async emptyList()
                    runCatching { lenzClient.searchLines(ip, port, keyword, server.cityName) }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }.flatten()

        // 同一线路可能被多个入口返回，按线路方向 id 去重
        val seen = HashSet<String>()
        return merged.mapNotNull { summary ->
            val fresh = summary.directions.filter { seen.add(it.lineId) }
            if (fresh.isEmpty()) null else summary.copy(directions = fresh)
        }
    }

    /**
     * 范县这类县级城市线路不多（20 多条），首页可以直接全部列出来，
     * 不用用户再去搜索。空关键字在朗斯协议里就是「列出全部线路」。
     */
    override suspend fun listAllLines(city: TransitCity): List<BusLineSummary>? =
        queryLines(city, "")

    override suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int
    ): List<NearbyStop> {
        return emptyList()
    }

    override suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop> {
        return emptyList()
    }

    override suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail {
        val lineRef = LenzIds.parseLineId(request.lineId)
            ?: LenzIds.LineRef(
                serverIp = city.lenzServerIp.orEmpty(),
                serverPort = city.lenzServerPort ?: 0,
                routeId = request.lineId.removePrefix(LenzIds.PREFIX),
                upDown = request.directionHint,
                lineName = request.lineNameHint
            )

        return lenzClient.getLineDetail(lineRef)
    }

    /**
     * 朗斯系统没有远程「完整班次表」接口，只下发两类真实数据：
     * - 随线路返回的首班 `fdtime` / 末班 `ldtime`；
     * - 查车辆位置时返回的下一班计划发车时间 `ndTime`。
     *
     * 这里如实呈现这两类数据，不推算、不编造中间班次。
     */
    override suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String?
    ): LineTimetable {
        val meta = lenzClient.lineMetaOf(line.lineId)

        val first = line.firstTime.ifBlank { meta.firstTime }
        val last = line.lastTime.ifBlank { meta.lastTime }
        val next = meta.nextDeparture

        val segments = if (first.isNotBlank() || last.isNotBlank()) {
            listOf(
                TimetableSegment(
                    fromTime = first.ifBlank { "首班" },
                    toTime = last.ifBlank { "末班" },
                    intervalTips = listOf(
                        first.takeIf { it.isNotBlank() }?.let { "首班 $it" }.orEmpty(),
                        last.takeIf { it.isNotBlank() }?.let { "末班 $it" }.orEmpty()
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                )
            )
        } else {
            emptyList()
        }

        val nextSource = if (next.isNotBlank()) "运营方实时下发" else null

        val desc = "本数据由朗斯公交调度系统实时下发，只含首末班与下一班发车时间；完整班次表未开放接口"

        return LineTimetable(
            lineId = line.lineId,
            lineName = line.name,
            nextDeparture = next.takeIf { it.isNotBlank() },
            nextDepartureSource = nextSource,
            todayDepartures = emptyList(),
            segments = segments,
            depDesc = desc
        )
    }
}
