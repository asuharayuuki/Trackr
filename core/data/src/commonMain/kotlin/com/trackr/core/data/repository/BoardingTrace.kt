package com.trackr.core.data.repository

import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.UserFix
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 判上车的判定记录（issue #49）：把单线路一轮轮询的输入和结论写成一行 JSON，交给 `BoardingTraceLog`。
 *
 * 记的是**输入**（定位、车、站）和判定前后的状态，不是各个条件的中间结果：有了输入，换一版规则也能原样重放。
 * 字段名故意写短，一轮一行，5 秒一轮也占不了多少地方：
 * - `t` 墙钟毫秒、`el` 单调毫秒、`sid` 会话、`line` / `stop` / `ord` 线路、候车站、站序
 * - `loc` 这一轮能不能用定位（前台服务带没带 location 类型、有没有精确定位权限）、`tier` 判定后的定位档位
 * - `fix` 服务定位、`appFix` App 前台定位：`lat` / `lng` / `acc` 精度 / `age` 这一刻的年龄（毫秒）；没有就没这个字段
 * - `buses` 候车站前后几站内的车：`id` / `o` 站序 / `p` 站间进度 / `arr` 到站标志 / `eta` / `lat` / `lng`
 * - `before` / `after` 判定前后的状态（见 [putState]）、`fx` 这一轮的效果
 * - `stops` 整条线的站点坐标 `[站序, 纬度, 经度]`，每次追踪只在第一行带一次（补判「人沿线过了站」要用）
 */
object BoardingTrace {

    /** 只记候车站前后这么多站内的车：更远的车和判上车无关 */
    private const val BUS_WINDOW_BEFORE = 3
    private const val BUS_WINDOW_AFTER = 5

    /**
     * 这一轮要不要记：只记开着「上车后自动结束追踪」的单线路（不含只追指定的车），
     * 而且判定前后至少有一头处在取定位的那一小段（车临近 → 到站 → 开走后的补判），或者这一轮判出了上车。
     * 不看这次能不能用定位——定位用不了本身就是要查的原因。
     */
    fun shouldRecord(
        before: TrackingSessionState,
        after: TrackingSessionState,
        effects: List<TrackingEngine.Effect>,
        stopAfterBoarding: Boolean
    ): Boolean {
        if (!stopAfterBoarding || before.legPhase != LegPhase.SINGLE || before.pinnedBusId != null) return false
        return LocationCadence.tierFor(before) != LocationCadence.Tier.OFF ||
            LocationCadence.tierFor(after) != LocationCadence.Tier.OFF ||
            effects.any { it is TrackingEngine.Effect.Finished.Boarded }
    }

    fun line(
        session: TrackingSession,
        after: TrackingSessionState,
        detail: BusLineDetail,
        effects: List<TrackingEngine.Effect>,
        locationEnabled: Boolean,
        serviceFix: UserFix?,
        appFix: UserFix?,
        nowMillis: Long,
        nowElapsedMillis: Long,
        includeStops: Boolean
    ): String {
        val before = session.state
        val order = before.targetOrder
        return buildJsonObject {
            put("t", nowMillis)
            put("el", nowElapsedMillis)
            put("sid", session.sessionId)
            put("line", before.lineName)
            put("stop", before.targetStopName)
            put("ord", order)
            put("loc", locationEnabled)
            put("tier", TrackingEngine.locationTier(after, locationEnabled, stopAfterBoarding = true).name)
            putFix("fix", serviceFix, nowElapsedMillis)
            putFix("appFix", appFix, nowElapsedMillis)
            putJsonArray("buses") {
                detail.buses
                    .filter { it.order in (order - BUS_WINDOW_BEFORE)..(order + BUS_WINDOW_AFTER) }
                    .forEach { bus ->
                        addJsonObject {
                            put("id", bus.busId)
                            put("o", bus.order)
                            put("p", bus.segmentProgress)
                            put("arr", bus.isArrived)
                            put("eta", bus.etaSeconds)
                            put("lat", bus.wgsLat)
                            put("lng", bus.wgsLng)
                        }
                    }
            }
            put("before", stateJson(before, nowMillis))
            put("after", stateJson(after, nowMillis))
            putJsonArray("fx") { effects.forEach { add(effectName(it)) } }
            if (includeStops) {
                putJsonArray("stops") {
                    detail.stops.forEach { stop ->
                        addJsonArray {
                            add(stop.order)
                            add(stop.wgsLat)
                            add(stop.wgsLng)
                        }
                    }
                }
            }
        }.toString()
    }

    private fun JsonObjectBuilder.putFix(name: String, fix: UserFix?, nowElapsedMillis: Long) {
        if (fix == null) return
        putJsonObject(name) {
            put("lat", fix.lat)
            put("lng", fix.lng)
            put("acc", fix.accuracyMeters)
            put("age", nowElapsedMillis - fix.elapsedMillis)
        }
    }

    /**
     * 判上车用到的状态：`bus` 正在追的车、`rem` 还剩几站、`arr` 到站标志、`cand` 待确认的车和它建了多久（`candAge`）、
     * `confirm` / `miss` 连续计数、`posEl` 第一个「像是上了车」的定位的单调时刻（和行里的 `el` 同一时基）、
     * `recent` 刚过站还在等定位的车、`near` 多久前在站旁、`watch` 补判开了多久。
     * 时间都写成「距这一刻多少毫秒」，没有的不写。
     */
    private fun stateJson(state: TrackingSessionState, nowMillis: Long): JsonObject = buildJsonObject {
        put("bus", state.currentBusId)
        put("busOrd", state.currentBusOrder)
        put("rem", state.stopsRemaining)
        put("arr", state.isBusArrivedAtStop)
        state.boardingCandidateBusId?.let {
            put("cand", it)
            put("candAge", nowMillis - state.boardingCandidateSinceMillis)
            put("confirm", state.boardingConfirmStreak)
            put("miss", state.boardingMissStreak)
        }
        if (state.boardingPositiveFixElapsedMillis >= 0L) put("posEl", state.boardingPositiveFixElapsedMillis)
        state.recentDepartedBusId?.let { put("recent", it) }
        if (state.nearWaitStopAtMillis != 0L) put("near", nowMillis - state.nearWaitStopAtMillis)
        if (state.boardWatchSinceMillis != 0L) put("watch", nowMillis - state.boardWatchSinceMillis)
    }

    private fun effectName(effect: TrackingEngine.Effect): String = when (effect) {
        is TrackingEngine.Effect.Alert -> "ALERT_${effect.kind.name}"
        is TrackingEngine.Effect.Finished.Boarded -> "BOARDED"
        is TrackingEngine.Effect.Finished -> "FINISHED"
        else -> "OTHER"
    }
}
