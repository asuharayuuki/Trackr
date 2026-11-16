package com.trackr.core.network.remote.lenz

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCity
import com.trackr.core.network.provider.lenz.LenzTransitProvider
import com.trackr.core.network.testing.unusedHttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 范县线路号（按当地公交公众号 1-12 路冬季时间表） */
class LenzRouteNumberTest {

    @Test
    fun testFanxianRouteNumbers() {
        assertEquals("1路", LenzApiClient.routeNumberFor("范县", "范县至王楼镇"))
        assertEquals("2路", LenzApiClient.routeNumberFor("范县", "范县至濮城镇"))
        assertEquals("3路", LenzApiClient.routeNumberFor("范县", "范县至彭楼水库"))
        assertEquals("5路", LenzApiClient.routeNumberFor("范县", "范县至杨集乡"))
        assertEquals("6路", LenzApiClient.routeNumberFor("范县", "范县至陈庄"))
        assertEquals("7路", LenzApiClient.routeNumberFor("范县", "范县至龙王庄镇"))
        assertEquals("8路", LenzApiClient.routeNumberFor("范县", "范县至陆集乡（线路1东行）"))
        assertEquals("10路", LenzApiClient.routeNumberFor("范县", "范县至颜村铺乡"))
        assertEquals("12路", LenzApiClient.routeNumberFor("范县", "范县至陈庄（石大庙）"))
    }

    @Test
    fun testSameRouteNumberIsNotReusedByVariants() {
        // 时间表里没有的支线 / 旅游专线 / 城际线不编号，避免 6 路出现好几次
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县至陈庄后张枣坡"))
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县至陈庄荷花园旅游专线"))
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县-杨集-二厂"))
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县至颜村铺旅游专线"))
        assertEquals(null, LenzApiClient.routeNumberFor("范县", "范县至台前"))
    }

    @Test
    fun testTwoElevenRoutesGetDistinguishingNotes() {
        assertEquals("11路 范县至高码头镇（线路1）", LenzApiClient.displayRouteName("范县", "范县至高码头镇"))
        assertEquals("11路 范县-仲庄-高码头（线路2）", LenzApiClient.displayRouteName("范县", "范县-仲庄-高码头"))
        assertEquals("8路 范县至陆集乡（线路1东行）", LenzApiClient.displayRouteName("范县", "范县至陆集乡（线路1东行）"))
    }

    @Test
    fun testUnknownCityKeepsOriginalName() {
        assertEquals(null, LenzApiClient.routeNumberFor("武夷", "武夷1路"))
        assertEquals("范县至台前", LenzApiClient.displayRouteName("范县", "范县至台前"))
        assertEquals("1路 范县至王楼镇", LenzApiClient.displayRouteName("范县", "范县至王楼镇"))
    }

    @Test
    fun testLenzTransitProviderGetTimetableOnlyRealData() = runTest {
        val client = LenzApiClient(unusedHttpClient())
        val provider = LenzTransitProvider(client)
        val city = TransitCity(cityId = "", cityName = "范县")
        val line = BusLineDirection(
            lineId = LenzIds.lineId("120.76.98.113", 8619, "937", 0, "范县至高码头镇"),
            name = "范县至高码头镇",
            direction = 0,
            startStop = "范县",
            endStop = "乔洼村",
            firstTime = "06:30",
            lastTime = "18:00",
            source = DataSourceType.LENZ
        )
        val timetable = provider.getTimetable(city, line, atTimeHHMM = "08:10")
        // 不编造完整班次表：今日班次列表为空，只呈现接口真实下发的首末班
        assertTrue(timetable.todayDepartures.isEmpty())
        assertEquals(1, timetable.segments.size)
        assertEquals("06:30", timetable.segments[0].fromTime)
        assertEquals("18:00", timetable.segments[0].toTime)
        assertTrue(timetable.segments[0].intervalTips.contains("首班 06:30"))
        assertTrue(timetable.segments[0].intervalTips.contains("末班 18:00"))
        // 没有实时 ndTime 下发时，下一班为空，不推算
        assertEquals(null, timetable.nextDeparture)
        assertEquals(null, timetable.nextDepartureSource)
        assertTrue(timetable.depDesc.contains("完整班次表未开放接口"))
    }
}
