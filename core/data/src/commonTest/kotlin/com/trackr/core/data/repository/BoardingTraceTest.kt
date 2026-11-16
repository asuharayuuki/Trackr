package com.trackr.core.data.repository

import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.UserFix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 判上车判定记录（issue #49）：什么时候记、一行里有什么 */
class BoardingTraceTest {

    private val now = 1_700_000_000_000L
    private val elapsed = 500_000L

    private fun begin(pinnedBusId: String = ""): TrackingSession = assertNotNull(
        TrackingEngine.begin(
            "s1", 1,
            TrackingTarget.SingleLine(
                cityId = "sh", lineId = "L49", lineName = "49路", endStopName = "终点站",
                targetStopId = "S10", targetStopName = "上海体育馆", targetOrder = 10, totalStops = 20,
                alertThresholdStops = 2, source = DataSourceType.MYGOLBS, direction = 1, pinnedBusId = pinnedBusId
            ),
            now
        )
    )

    private fun bus(id: String, order: Int) =
        LiveBus(busId = id, plateOrFleetNo = id, order = order, wgsLat = 31.0 + order / 1000.0, wgsLng = 121.0)

    private fun detail(buses: List<LiveBus>) = BusLineDetail(
        line = BusLineDirection(lineId = "L49", name = "49路", startStop = "起点", endStop = "终点"),
        stops = (1..20).map { BusStop(stopId = "S$it", name = "站$it", order = it, wgsLat = 31.0 + it / 1000.0, wgsLng = 121.0) },
        buses = buses
    )

    private fun poll(session: TrackingSession, buses: List<LiveBus>, fix: UserFix?) =
        TrackingEngine.reducePoll(session, detail(buses), fix != null, fix, null, now + 5_000, elapsed, stopAfterBoarding = true)

    @Test
    fun shouldRecord_onlyAroundTheBoardingWindow() {
        val session = begin()
        // 车还远（6 站外）：不取定位，也不记
        val far = poll(session, listOf(bus("b1", 4)), fix = null)
        assertFalse(BoardingTrace.shouldRecord(session.state, far.session.state, far.effects, stopAfterBoarding = true))
        // 车临近（还剩 1 站）：开始记，定位用不了也记
        val near = poll(far.session, listOf(bus("b1", 9)), fix = null)
        assertTrue(BoardingTrace.shouldRecord(far.session.state, near.session.state, near.effects, stopAfterBoarding = true))
        // 开关关着、只追指定的车：不记
        assertFalse(BoardingTrace.shouldRecord(far.session.state, near.session.state, near.effects, stopAfterBoarding = false))
        val pinned = begin(pinnedBusId = "b1")
        assertFalse(BoardingTrace.shouldRecord(pinned.state, near.session.state, near.effects, stopAfterBoarding = true))
    }

    @Test
    fun line_hasInputsStatesAndVerdict() {
        val session = begin()
        // 人在候车站上，精度 20 m，定位是 4 秒前的
        val fix = UserFix(lat = 31.010, lng = 121.0, accuracyMeters = 20f, elapsedMillis = elapsed - 4_000)
        val buses = listOf(bus("b1", 10), bus("far", 2), bus("gone", 17))
        val result = poll(session, buses, fix)

        val text = BoardingTrace.line(session, result.session.state, detail(buses), result.effects,
            locationEnabled = true, serviceFix = fix, appFix = null, nowMillis = now + 5_000, nowElapsedMillis = elapsed, includeStops = true)
        assertFalse(text.contains('\n'), "一条记录一行")
        val json = Json.parseToJsonElement(text).jsonObject

        assertEquals(now + 5_000, json.getValue("t").jsonPrimitive.long)
        assertEquals(10, json.getValue("ord").jsonPrimitive.int)
        assertTrue(json.getValue("loc").jsonPrimitive.boolean)
        assertEquals("BOARDING_CHECK", json.getValue("tier").jsonPrimitive.content)
        assertEquals(4_000L, json.getValue("fix").jsonObject.getValue("age").jsonPrimitive.long)
        assertNull(json["appFix"])
        // 只记候车站前后几站内的车
        assertEquals(listOf("b1"), json.getValue("buses").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        // 判定前没有待确认，这一轮建上了
        assertNull(json.getValue("before").jsonObject["cand"])
        assertEquals("b1", json.getValue("after").jsonObject.getValue("cand").jsonPrimitive.content)
        assertEquals(20, json.getValue("stops").jsonArray.size)

        // 之后的行不再带站点坐标
        val later = BoardingTrace.line(session, result.session.state, detail(buses), result.effects,
            locationEnabled = false, serviceFix = null, appFix = null, nowMillis = now + 10_000, nowElapsedMillis = elapsed, includeStops = false)
        val laterJson = Json.parseToJsonElement(later).jsonObject
        assertNull(laterJson["stops"])
        assertNull(laterJson["fix"])
        assertEquals("OFF", laterJson.getValue("tier").jsonPrimitive.content)
    }
}
