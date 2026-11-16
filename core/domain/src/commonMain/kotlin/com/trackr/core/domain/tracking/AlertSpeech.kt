package com.trackr.core.domain.tracking

import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSessionState

/**
 * 「语音播报提醒」（issue #52）念的话：一条提醒效果对应一句，念给人听，所以不带符号、不写站序。
 * 结束类效果（上车后结束、路线走完）不念：前一条到站提醒已经说过了。
 */
object AlertSpeech {

    /** [effect] 要念的一句；不用念时为 null */
    fun sentenceFor(effect: TrackingEngine.Effect): String? = when (effect) {
        is TrackingEngine.Effect.Alert -> alertSentence(effect.kind, effect.state)
        is TrackingEngine.Effect.TransferAlert ->
            "已到换乘站${effect.stationName}，请下车换乘${effect.nextLineName}"
        is TrackingEngine.Effect.Finished -> null
    }

    private fun alertSentence(kind: TrackingEngine.AlertKind, state: TrackingSessionState): String {
        // 只追一辆车（多半坐在车上等下车站）和路线的乘车阶段：该下车了；其余是在等车
        val getOff = state.isPinnedBus || state.legPhase == LegPhase.ON_BOARD
        val subject = if (state.isPinnedBus && state.pinnedPlate.isNotBlank()) {
            "${state.lineName}，车牌${state.pinnedPlate}"
        } else {
            state.lineName
        }
        val stop = state.targetStopName
        return when (kind) {
            TrackingEngine.AlertKind.ARRIVED ->
                if (getOff) "$subject 已到达$stop，请下车" else "$subject 已到达$stop，请上车"
            TrackingEngine.AlertKind.APPROACHING -> {
                val minutes = if (state.etaSeconds > 0) "，大约${(state.etaSeconds + 59) / 60}分钟" else ""
                val stops = if (state.stopsRemaining > 0) "还有${state.stopsRemaining}站到$stop" else "下一站就是$stop"
                if (getOff) "$subject $stops$minutes，请准备下车" else "$subject $stops$minutes，请准备上车"
            }
            TrackingEngine.AlertKind.MISSED -> "${state.lineName} 已经开走了，正在帮你等下一班"
        }
    }
}
