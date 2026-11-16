package com.trackr.core.domain.tracking

import com.trackr.core.domain.arrival.ArrivalRules
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSessionState

/**
 * 单线路（非分组）候车追踪的纯函数核心：选车 + 提醒编排。
 *
 * 规则：
 * - 只考虑未开过目标站的车（order <= targetOrder），优先沿用正在追踪的车，否则取离目标站最近的一辆。
 * - 正在追踪的车短暂从数据里消失时，保持原车 [MAX_MISSING_POLLS] 轮，不立刻换车。
 * - 正在追踪的车已开过目标站或消失超过上限时换车；换车时重置提醒触发标志，下一辆车照常提醒。
 * - 开着「上车后自动结束追踪」时，车停到候车站就开始用定位确认人有没有跟着车走（见 [checkBoarding]），
 *   确认上了就发 `boarded` 让调用方结束这次追踪（issue #32）；不要求贴车（issue #49），但要相隔至少
 *   [BoardingDetector.CONFIRM_MIN_GAP_MILLIS] 的两个定位都显示人跟着车走了才算，补判也一样——判定规则和路线、
 *   一起等是同一套（[BoardingDetector.evaluateCandidate]）；
 *   确认期间后面那班车不提醒。
 * - 判上车不留死角（issue #49）：
 *   - 站旁记忆：等车时定位在站旁就记下（[TrackingSessionState.nearWaitStopAtMillis]），车到站 / 过站那一轮没定位，
 *     凭记忆照样开始确认；车过站后最多 [RECOVERY_MAX_STOPS_PAST] 站、[DEPARTURE_RECOVERY_WINDOW_MILLIS] 内都能补建；
 *   - 补判：一开始确认就记下 [TrackingSessionState.boardWatchSinceMillis]，之后 [BOARD_WATCH_MILLIS] 内即使待确认
 *     超时放掉了，只要定位显示人在这条线路上、已经过了站（[BoardingDetector.isPastStopAlongLine]）就判上车；
 *     只有「没赶上」才撤掉补判；
 *   - 「没赶上」要有车的位置作证（车已过站且开远），车只是这一轮不在数据里不算，而且要连续 [BoardingDetector.MISS_STREAK] 次；
 *   - 不因为一次不符合就当成没上车：车过站后 [DEPARTURE_RECOVERY_WINDOW_MILLIS] 内每一轮都重新判要不要开始确认，
 *     某一轮线路数据里缺候车站坐标也不清掉已有的判定。
 * - 指定了车（[TrackingSessionState.pinnedBusId]，issue #52「下车提醒」）时只追这一辆：不换车、不判上车，
 *   它暂时从数据里消失就一直保持上次位置；开过目标站后发 `finished` 让调用方结束追踪（没发过到站提醒的先补发）。
 */
internal object SingleLegTracker {

    /** 追踪车辆短暂消失时最多保持原车的轮询次数 */
    const val MAX_MISSING_POLLS = 2

    /**
     * 车过站后补建待确认的时限：上一轮离现在不超过这么久（避免用中断前的旧车辆启动检测），
     * 「车刚过站、定位还没来」也最多等这么久。issue #49：以前 60 秒，上车时 GPS 冷启动、后台限频常常赶不上
     */
    private const val DEPARTURE_RECOVERY_WINDOW_MILLIS = 3 * 60_000L

    /** 车过站后补建待确认：车最多开过候车站几站（上游数据常常一跳两三站） */
    private const val RECOVERY_MAX_STOPS_PAST = 3

    /** 站旁记忆的有效期：这么久以内在站旁出现过，车到站 / 过站那一轮没定位也照样开始确认 */
    private const val NEAR_STOP_MEMORY_MILLIS = 3 * 60_000L

    /** 补判上车的时长：有车到站 / 过站后这么久以内，人沿线过了站都算上了车 */
    const val BOARD_WATCH_MILLIS = 20 * 60_000L

    /** 判出上车后写进状态的文案（会话马上就被清掉了，只有通知里会读到它） */
    const val BOARDED_SUMMARY = "已上车，本次追踪结束"

    /** 单线路提醒类型 */
    enum class AlertKind { APPROACHING, ARRIVED }

    /**
     * 单次轮询结果。
     * @param state 更新后的会话状态（已更新提醒触发标志）
     * @param alert 本轮要发的提醒，null 表示不发
     * @param alertState 发提醒时用的状态
     * @param boarded 定位判出已上车：调用方据此结束这次追踪
     * @param finished 指定的那辆车已开过目标站（issue #52）：调用方据此结束这次追踪
     */
    data class SingleUpdate(
        val state: TrackingSessionState,
        val alert: AlertKind? = null,
        val alertState: TrackingSessionState? = null,
        val boarded: Boolean = false,
        val finished: Boolean = false
    )

    /**
     * 处理一次单线路轮询。
     *
     * @param session 当前会话状态（legPhase == SINGLE）
     * @param buses 本次实时数据中该线路的在途车辆
     * @param operatingState 线路运营状态（无车时用于文案）
     * @param statusDescription 上游运营状态描述（无车时用于文案）
     * @param nowMillis 当前墙钟时刻（毫秒），写进 `updatedAtMillis`
     * @param boarding 判上车的定位上下文（候车站 = 目标站）；null 表示这次不判上车（开关关着 / 没定位），
     *   行为和原来一样
     * @param lineStops 这条线的站点坐标（WGS-84），补判「人沿线过了站」用；空表示不补判
     */
    fun processPoll(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        operatingState: LineOperatingState = LineOperatingState.RUNNING,
        statusDescription: String = "",
        nowMillis: Long,
        boarding: BoardingDetector.Input? = null,
        lineStops: List<BoardingDetector.LinePoint> = emptyList()
    ): SingleUpdate {
        session.pinnedBusId?.let { pinnedId ->
            return processPinnedBus(session, pinnedId, buses, operatingState, statusDescription, nowMillis)
        }
        if (boarding == null) {
            // 不判上车了（开关关了 / 定位不能用了）：留着的待确认要清掉，否则一直压着提醒、一直开高频定位
            val cleaned = if (session.boardingCandidateBusId != null || session.recentDepartedBusId != null ||
                session.nearWaitStopAtMillis != 0L || session.boardWatchSinceMillis != 0L
            ) {
                clearAllBoarding(session)
            } else {
                session
            }
            return processBus(cleaned, buses, operatingState, statusDescription, nowMillis)
        }

        var remembered = resetPositiveWhenBackAtStop(rememberNearStop(session, boarding, nowMillis), boarding)
        // 补判：车到站 / 过站之后，人沿线过了站就是上了车——不看车的上报位置，也不管待确认还在不在
        if (remembered.boardWatchSinceMillis != 0L &&
            BoardingDetector.isPastStopAlongLine(boarding, lineStops, session.targetOrder)
        ) {
            val confirmed = confirmPositive(remembered, boarding)
            if (confirmed.boarded) {
                return SingleUpdate(clearAllBoarding(confirmed.state).copy(statusSummary = BOARDED_SUMMARY), boarded = true)
            }
            remembered = confirmed.state
        }
        val recovered = recoverDepartedCandidate(remembered, buses, boarding, nowMillis)
        val checked = checkBoarding(recovered, buses, boarding, nowMillis)
        if (checked.boarded) {
            return SingleUpdate(clearAllBoarding(checked.state).copy(statusSummary = BOARDED_SUMMARY), boarded = true)
        }
        val update = processBus(checked.state, buses, operatingState, statusDescription, nowMillis)
        return update.copy(state = startCandidate(update.state, boarding, nowMillis))
    }

    /** 选车 + 提醒（原来的全部行为），不含上车判定 */
    private fun processBus(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        operatingState: LineOperatingState,
        statusDescription: String,
        nowMillis: Long
    ): SingleUpdate {
        val targetOrder = session.targetOrder
        val trackedId = session.currentBusId

        // 1. 正在追踪的车短暂消失：保持原车一两轮
        if (trackedId != null && buses.none { it.busId == trackedId } &&
            session.missingBusPolls < MAX_MISSING_POLLS
        ) {
            return SingleUpdate(
                session.copy(
                    missingBusPolls = session.missingBusPolls + 1,
                    statusSummary = "车辆信号短暂丢失，保持上次位置（距 ${session.targetStopName}）",
                    updatedAtMillis = nowMillis
                )
            )
        }

        // 2. 选车：只看未过站车辆，优先沿用正在追踪的车
        val candidates = buses.filter { it.order <= targetOrder }
        val tracked = trackedId?.let { id -> candidates.find { it.busId == id } }
        val best = tracked ?: candidates.maxByOrNull { it.order.toFloat() + it.segmentProgress }

        if (best == null) {
            val summary = when {
                operatingState != LineOperatingState.RUNNING ->
                    statusDescription.ifEmpty { operatingState.defaultLabel }
                buses.isNotEmpty() -> "车辆均已过站，等待下一班车调度"
                else -> "暂无在途车辆运营数据"
            }
            return SingleUpdate(
                session.copy(
                    currentBusId = null,
                    stopsRemaining = -1,
                    etaSeconds = -1,
                    isBusArrivedAtStop = false,
                    missingBusPolls = 0,
                    // 原车已过站 / 消失：下一辆车出现时重新提醒
                    hasTriggeredApproachingAlert = false,
                    hasTriggeredArrivedAlert = false,
                    statusSummary = summary,
                    updatedAtMillis = nowMillis
                )
            )
        }

        val isNewVehicle = trackedId == null || trackedId != best.busId
        val stopsRemaining = (targetOrder - best.order).coerceAtLeast(0)
        val isStoppedAtTarget = stopsRemaining == 0 && ArrivalRules.isBusStopped(best)
        val isArrivedCondition = ArrivalRules.isBusArrivedAtOrder(targetOrder, best)

        // 换车或初次锁定：重新确定起始追踪基准站
        val startOrder = if (isNewVehicle) {
            val base = if (ArrivalRules.isBusStopped(best)) best.order else best.order - 1
            base.coerceAtMost(targetOrder - 1).coerceAtLeast(1)
        } else {
            session.startTrackingOrder
        }

        // 换车时重置提醒触发标志
        var hasApproaching = if (isNewVehicle) false else session.hasTriggeredApproachingAlert
        var hasArrived = if (isNewVehicle) false else session.hasTriggeredArrivedAlert

        val etaFormatted = RouteProjectionMath.formatEtaText(best.etaSeconds, isStoppedAtTarget)
        val statusSummary = when {
            isStoppedAtTarget -> "车辆已到达 ${session.targetStopName}！"
            stopsRemaining == 0 -> "车辆即将进站 ${session.targetStopName}"
            best.etaSeconds > 0 -> "距 ${session.targetStopName} 还有 $stopsRemaining 站（$etaFormatted）"
            else -> "距 ${session.targetStopName} 还有 $stopsRemaining 站"
        }

        val baseState = session.copy(
            startTrackingOrder = startOrder,
            currentBusId = best.busId,
            currentBusOrder = best.order,
            currentBusProgress = best.segmentProgress,
            isBusArrivedAtStop = isStoppedAtTarget,
            stopsRemaining = stopsRemaining,
            etaSeconds = best.etaSeconds,
            expectedArrivalTime = best.expectedArrivalTimeText,
            missingBusPolls = 0,
            statusSummary = statusSummary,
            updatedAtMillis = nowMillis
        )

        // 正在确认是否上了前一班车：后面这班车的临近 / 到站先不提醒（issue #49：人已在车上，下一班到站又提醒）。
        // 判成没赶上会清掉待确认，后面这班车照常提醒（标志没被置上）
        // 只压「后面那班车」：待确认的就是这辆车时照常提醒——车一进最后一段（剩 0 站）就会建待确认，
        // 那时它还没到站，把它自己的到站提醒也压掉就等于开着自动结束时不提醒到站了
        val suppressAlerts = session.boardingCandidateBusId != null && session.boardingCandidateBusId != best.busId
        var alert: AlertKind? = null
        var alertState: TrackingSessionState? = null
        if (!suppressAlerts) {
            if (isArrivedCondition && !hasArrived) {
                alert = AlertKind.ARRIVED
                alertState = baseState.copy(stopsRemaining = 0, isBusArrivedAtStop = true)
                hasArrived = true
                hasApproaching = true
            } else if (!isArrivedCondition && stopsRemaining <= session.alertThresholdStops && !hasApproaching) {
                alert = AlertKind.APPROACHING
                alertState = baseState
                hasApproaching = true
            }
        }

        return SingleUpdate(
            baseState.copy(
                hasTriggeredApproachingAlert = hasApproaching,
                hasTriggeredArrivedAlert = hasArrived
            ),
            alert,
            alertState
        )
    }

    // ---------------------------------------------------------------- 只追指定的车（issue #52）

    /**
     * 只追 [pinnedId] 这一辆：提醒规则和不指定时一样（还剩阈值站数提醒一次、到站提醒一次）。
     * - 车不在数据里：保持上次位置接着等，不换车（指定的车可能只是一会儿没信号）；
     * - 车开过目标站：没发过到站提醒的先补发，然后 `finished`。
     */
    private fun processPinnedBus(
        session: TrackingSessionState,
        pinnedId: String,
        buses: List<LiveBus>,
        operatingState: LineOperatingState,
        statusDescription: String,
        nowMillis: Long
    ): SingleUpdate {
        val targetOrder = session.targetOrder
        val plate = session.pinnedPlate.ifBlank { "这辆车" }
        val bus = buses.firstOrNull { it.busId == pinnedId }
        if (bus == null) {
            val summary = when {
                session.currentBusId == null && operatingState != LineOperatingState.RUNNING ->
                    statusDescription.ifEmpty { operatingState.defaultLabel }
                session.currentBusId == null -> "正在查找 $plate 的实时位置…"
                else -> "$plate 信号暂时丢失，保持上次位置（距 ${session.targetStopName}）"
            }
            return SingleUpdate(
                session.copy(
                    missingBusPolls = session.missingBusPolls + 1,
                    statusSummary = summary,
                    updatedAtMillis = nowMillis
                )
            )
        }

        val isFirstSight = session.currentBusId != pinnedId
        val passed = bus.order > targetOrder
        val stopsRemaining = (targetOrder - bus.order).coerceAtLeast(0)
        val isStoppedAtTarget = stopsRemaining == 0 && !passed && ArrivalRules.isBusStopped(bus)
        val isArrivedCondition = ArrivalRules.isBusArrivedAtOrder(targetOrder, bus)
        val startOrder = if (isFirstSight) {
            val base = if (ArrivalRules.isBusStopped(bus)) bus.order else bus.order - 1
            base.coerceAtMost(targetOrder - 1).coerceAtLeast(1)
        } else {
            session.startTrackingOrder
        }
        val etaFormatted = RouteProjectionMath.formatEtaText(bus.etaSeconds, isStoppedAtTarget)
        val statusSummary = when {
            passed || isStoppedAtTarget -> "$plate 已到达 ${session.targetStopName}！"
            stopsRemaining == 0 -> "$plate 即将进站 ${session.targetStopName}"
            bus.etaSeconds > 0 -> "$plate 距 ${session.targetStopName} 还有 $stopsRemaining 站（$etaFormatted）"
            else -> "$plate 距 ${session.targetStopName} 还有 $stopsRemaining 站"
        }
        val baseState = session.copy(
            startTrackingOrder = startOrder,
            currentBusId = pinnedId,
            currentBusOrder = bus.order.coerceAtMost(targetOrder),
            currentBusProgress = if (passed) 1f else bus.segmentProgress,
            isBusArrivedAtStop = isStoppedAtTarget || passed,
            stopsRemaining = stopsRemaining,
            etaSeconds = if (passed) 0 else bus.etaSeconds,
            expectedArrivalTime = bus.expectedArrivalTimeText,
            missingBusPolls = 0,
            statusSummary = statusSummary,
            updatedAtMillis = nowMillis
        )

        var hasApproaching = session.hasTriggeredApproachingAlert
        var hasArrived = session.hasTriggeredArrivedAlert
        var alert: AlertKind? = null
        var alertState: TrackingSessionState? = null
        if (isArrivedCondition && !hasArrived) {
            alert = AlertKind.ARRIVED
            alertState = baseState.copy(stopsRemaining = 0, isBusArrivedAtStop = true)
            hasArrived = true
            hasApproaching = true
        } else if (!isArrivedCondition && stopsRemaining <= session.alertThresholdStops && !hasApproaching) {
            alert = AlertKind.APPROACHING
            alertState = baseState
            hasApproaching = true
        }
        return SingleUpdate(
            baseState.copy(
                hasTriggeredApproachingAlert = hasApproaching,
                hasTriggeredArrivedAlert = hasArrived
            ),
            alert,
            alertState,
            finished = passed
        )
    }

    // ---------------------------------------------------------------- 判上车（issue #32）

    private class BoardingCheck(val state: TrackingSessionState, val boarded: Boolean)

    /**
     * 待确认阶段的一轮判定：规则全在 [BoardingDetector.evaluateCandidate]（和路线、一起等同一套）。
     * 单线路自己的只有两点：超时兜底给的结论不认（只放掉待确认，补判 [TrackingSessionState.boardWatchSinceMillis] 留着）；
     * 人贴着紧跟的另一辆车走了就改确认那一辆。
     */
    private fun checkBoarding(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        input: BoardingDetector.Input,
        nowMillis: Long
    ): BoardingCheck {
        val candidateId = session.boardingCandidateBusId ?: return BoardingCheck(session, false)
        // 车开过候车站之后就不再是「未过站的车」了，所以按 id 在全部在途车里找它
        val candidate = buses.firstOrNull { it.busId == candidateId }
        // 候车站就是目标站：boardStopOrder = targetOrder，和路线、一起等走同一套 [BoardingDetector.evaluateCandidate]
        val probe = session.copy(boardStopOrder = session.targetOrder)
        val evaluated = BoardingDetector.evaluateCandidate(probe, candidate, input)
        val state = session.copy(
            boardingConfirmStreak = evaluated.state.boardingConfirmStreak,
            boardingMissStreak = evaluated.state.boardingMissStreak,
            boardingPositiveFixElapsedMillis = evaluated.state.boardingPositiveFixElapsedMillis,
            boardingLastFixElapsedMillis = evaluated.state.boardingLastFixElapsedMillis
        )
        // 超时兜底给的结论单线路不认（人可能正往站上走）：只放掉待确认，补判和「像是上了车」的那个定位留着
        if (evaluated.byTimeout ||
            (evaluated.verdict == BoardingDetector.Verdict.PENDING &&
                nowMillis - session.boardingCandidateSinceMillis >= BoardingDetector.CANDIDATE_TIMEOUT_MILLIS)
        ) {
            return BoardingCheck(
                BoardingDetector.clearBoardingFields(state)
                    .copy(boardingPositiveFixElapsedMillis = state.boardingPositiveFixElapsedMillis),
                false
            )
        }
        // 原候选车上还没有任何「像是上了车」的迹象、人也没贴着它，而同线路紧跟的另一辆车已到 / 刚过目标站且人正贴着那辆车走：
        // 改追那辆实际坐上的车（前后车串车或前车停靠未上、上了后车的情形）
        if (evaluated.verdict == BoardingDetector.Verdict.PENDING &&
            state.boardingPositiveFixElapsedMillis < 0L &&
            (candidate == null || !BoardingDetector.isUserRidingBus(input, candidate))
        ) {
            val otherRiding = buses.firstOrNull {
                it.busId != candidateId &&
                    it.order in session.targetOrder..(session.targetOrder + 1) &&
                    BoardingDetector.isUserRidingBus(input, it)
            }
            if (otherRiding != null) {
                val switched = withCandidate(session, otherRiding.busId, nowMillis)
                return checkBoarding(switched, buses, input, nowMillis)
            }
        }
        return when (evaluated.verdict) {
            BoardingDetector.Verdict.BOARDED -> BoardingCheck(BoardingDetector.clearBoardingFields(state), true)
            // 没赶上：连续几个定位都显示人还在站上、车已过站开远了，这辆车不会再「补判」成上了
            BoardingDetector.Verdict.MISSED ->
                BoardingCheck(BoardingDetector.clearBoardingFields(state).copy(boardWatchSinceMillis = 0L), false)
            BoardingDetector.Verdict.PENDING -> BoardingCheck(state, false)
        }
    }

    /**
     * 轮询可能直接从进站前跳到离站后，或车到站那轮还没取得定位。
     * 必须在选下一班车之前补回原车；只认最近追过的同一辆车、刚过 1–[RECOVERY_MAX_STOPS_PAST] 站，
     * 且人在站旁、已随车离开，或者这一轮没定位但刚才在站旁（站旁记忆）。
     * 此处只创建待确认，是否上车仍由定位判断，不能因车过站直接结束。
     */
    private fun recoverDepartedCandidate(
        session: TrackingSessionState,
        buses: List<LiveBus>,
        input: BoardingDetector.Input,
        nowMillis: Long
    ): TrackingSessionState {
        if (session.boardingCandidateBusId != null) return session

        // 1. 已经记下的「刚过站的车」：时限内每一轮都重新判。一次定位不符合（人刚上车还没离站、定位飘了、
        //    车这一轮不在数据里）不代表没上车，不能就此放弃——只有超时、或车开远了才不再补
        val recentId = session.recentDepartedBusId
        if (recentId != null) {
            if (nowMillis - session.recentDepartedAtMillis !in 0..DEPARTURE_RECOVERY_WINDOW_MILLIS) {
                return session.copy(recentDepartedBusId = null, recentDepartedAtMillis = 0L)
            }
            val recentBus = buses.firstOrNull { it.busId == recentId }
            if (recentBus != null && recentBus.order > session.targetOrder + RECOVERY_MAX_STOPS_PAST) {
                return session.copy(recentDepartedBusId = null, recentDepartedAtMillis = 0L)
            }
            return if (shouldRecover(session, recentBus, input, nowMillis)) withCandidate(session, recentId, nowMillis) else session
        }

        // 2. 检查上一轮还在候车站（或前两站内）的当前追踪车辆是否本轮刚过站
        if (nowMillis - session.updatedAtMillis !in 0..DEPARTURE_RECOVERY_WINDOW_MILLIS) return session
        if (session.currentBusOrder !in (session.targetOrder - 2)..session.targetOrder) return session
        val previousId = session.currentBusId ?: return session
        val departed = buses.firstOrNull { it.busId == previousId } ?: return session
        // 上游数据常常一跳两站（车不停站、或这一轮轮询晚了），只认刚过一站会整段漏掉判定（issue #41）
        if (departed.order !in (session.targetOrder + 1)..(session.targetOrder + RECOVERY_MAX_STOPS_PAST)) return session
        if (shouldRecover(session, departed, input, nowMillis)) return withCandidate(session, previousId, nowMillis)
        // 这一轮还看不出来（没定位、或定位不符合）：记下这辆车，保持高频定位与轮询，时限内接着判
        return session.copy(recentDepartedBusId = previousId, recentDepartedAtMillis = nowMillis)
    }

    /**
     * 刚过站的车要不要补建待确认：刚才在站旁（站旁记忆），或这一刻的定位在站旁 / 已经跟着车走了。
     * 车这一轮不在数据里时只看人在不在站旁——没有车的位置，「跟着车走」无从判断。
     */
    private fun shouldRecover(
        session: TrackingSessionState,
        bus: LiveBus?,
        input: BoardingDetector.Input,
        nowMillis: Long
    ): Boolean {
        if (nearStopRecently(session, nowMillis)) return true
        if (input.user == null && input.coarseUser == null) return false
        if (bus == null) return BoardingDetector.shouldStartCandidate(input)
        return BoardingDetector.shouldRecoverDepartedCandidate(input, bus, session.targetOrder)
    }

    /**
     * 车到候车站、人也在站附近：开始用定位确认（没有定位、人明显不在站附近时不创建）。
     *
     * 用 `stopsRemaining == 0` 而不是「已停靠」：公交往往不停稳就过站，数据里也常常只有站序没有停靠状态，
     * 只看停靠会漏掉一整轮判定。
     */
    private fun startCandidate(
        state: TrackingSessionState,
        input: BoardingDetector.Input,
        nowMillis: Long
    ): TrackingSessionState {
        if (state.boardingCandidateBusId != null || state.stopsRemaining != 0) return state
        val busId = state.currentBusId ?: return state
        val hasFix = input.user != null || input.coarseUser != null
        // 有定位按定位；这一轮没定位就看站旁记忆（车到站那一刻恰好没定位，issue #49）
        val start = if (hasFix) BoardingDetector.shouldStartCandidate(input) else nearStopRecently(state, nowMillis)
        if (!start) return state
        return withCandidate(state, busId, nowMillis)
    }

    /** 记下站旁记忆；补判过期了就撤掉 */
    private fun rememberNearStop(
        state: TrackingSessionState,
        input: BoardingDetector.Input,
        nowMillis: Long
    ): TrackingSessionState {
        var next = state
        if (BoardingDetector.isNearBoardStop(input)) next = next.copy(nearWaitStopAtMillis = nowMillis)
        if (next.boardWatchSinceMillis != 0L && nowMillis - next.boardWatchSinceMillis !in 0..BOARD_WATCH_MILLIS) {
            next = next.copy(boardWatchSinceMillis = 0L)
        }
        return next
    }

    private fun nearStopRecently(state: TrackingSessionState, nowMillis: Long): Boolean =
        state.nearWaitStopAtMillis != 0L && nowMillis - state.nearWaitStopAtMillis in 0..NEAR_STOP_MEMORY_MILLIS

    /** 判上车相关的字段全部清掉（判出上车、不再判上车时用） */
    private fun clearAllBoarding(state: TrackingSessionState): TrackingSessionState =
        BoardingDetector.clearBoardingFields(state)
            .copy(nearWaitStopAtMillis = 0L, boardWatchSinceMillis = 0L, boardingPositiveFixElapsedMillis = -1L)

    /** 补判阶段用的同一条「两个定位」规则（[BoardingDetector.confirmPositive]） */
    private fun confirmPositive(state: TrackingSessionState, input: BoardingDetector.Input): BoardingCheck {
        val fix = input.user ?: input.coarseUser ?: return BoardingCheck(state, false)
        val (first, confirmed) = BoardingDetector.confirmPositive(state.boardingPositiveFixElapsedMillis, fix)
        return BoardingCheck(state.copy(boardingPositiveFixElapsedMillis = first), confirmed)
    }

    /** 精定位显示人还在（又回到）候车站上：之前那次「像是上了车」作废 */
    private fun resetPositiveWhenBackAtStop(state: TrackingSessionState, input: BoardingDetector.Input): TrackingSessionState =
        if (state.boardingPositiveFixElapsedMillis >= 0L && BoardingDetector.isBackAtBoardStop(input)) {
            state.copy(boardingPositiveFixElapsedMillis = -1L)
        } else {
            state
        }

    private fun withCandidate(
        state: TrackingSessionState,
        busId: String,
        nowMillis: Long
    ): TrackingSessionState = state.copy(
        boardingCandidateBusId = busId,
        boardingCandidateSinceMillis = nowMillis,
        boardingConfirmStreak = 0,
        boardingMissStreak = 0,
        boardingLastFixElapsedMillis = -1L,
        recentDepartedBusId = null,
        recentDepartedAtMillis = 0L,
        // 开始确认就开始补判：之后待确认放掉了，人沿线过了站也照样算上车
        boardWatchSinceMillis = nowMillis
    )
}
