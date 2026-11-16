package com.trackr.core.network.provider

import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest

/**
 * 实时公交统一数据源 Provider 标准契约
 */
interface ITransitProvider {
    val sourceType: DataSourceType

    fun supportsLine(lineId: String): Boolean
    fun supportsStop(request: StopDetailRequest): Boolean
    fun supportsLocationRadar(): Boolean

    /**
     * 站点接口是否直接带回车辆明细（含全线站序）。
     *
     * 车来了的站点接口带每辆车的站序，可以直接用来判断「几站到」；
     * 掌上公交的站点接口只给「x 站」文字，要车辆明细必须再按线路查。
     */
    fun stopDetailIncludesBuses(): Boolean = true

    /**
     * 这个城市此刻能不能查到数据。
     *
     * 车来了 / 掌上公交是统一公网入口，恒为 true；掌上出行是各城市自己一台服务器，
     * 有些城市的服务器常年不在线，需要真连一次才知道。默认 true，别的实现不用改。
     */
    suspend fun isCityReachable(city: TransitCity): Boolean = true

    /**
     * 这个城市的全部线路（首页直接列出来用）。
     * 返回 null 表示该数据源不支持「列全部」，调用方应退回搜索。
     */
    suspend fun listAllLines(city: TransitCity): List<BusLineSummary>? = null

    suspend fun getSupportedCities(): List<TransitCity>
    suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String?
    /** 逆地理区县名（未清洗，如 "北流市"、"玉州区"）；不支持的数据源返回 null */
    suspend fun resolveDistrictByLocation(wgsLat: Double, wgsLng: Double): String? = null
    suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double? = null,
        userLng: Double? = null
    ): TransitSearchResult
    suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4
    ): List<NearbyStop>
    suspend fun getStopDetail(
        city: TransitCity,
        request: StopDetailRequest
    ): List<NearbyStop>
    suspend fun getLineDetail(
        city: TransitCity,
        request: LineDetailRequest
    ): BusLineDetail
    suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String? = null
    ): LineTimetable
}
