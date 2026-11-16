package com.trackr.core.domain.tracking

import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 「语音播报提醒」念的话（issue #52） */
class AlertSpeechTest {

    private val waiting = TrackingSessionState(
        isActive = true,
        lineName = "614路",
        targetStopName = "长乐职专站",
        stopsRemaining = 2,
        etaSeconds = 250,
        legPhase = LegPhase.SINGLE,
        updatedAtMillis = 0L
    )

    private fun alert(kind: TrackingEngine.AlertKind, state: TrackingSessionState) =
        AlertSpeech.sentenceFor(TrackingEngine.Effect.Alert(kind, state))

    @Test
    fun waitingForBus_saysGetOn() {
        assertEquals("614路 还有2站到长乐职专站，大约5分钟，请准备上车", alert(TrackingEngine.AlertKind.APPROACHING, waiting))
        assertEquals("614路 已到达长乐职专站，请上车", alert(TrackingEngine.AlertKind.ARRIVED, waiting))
        assertEquals(
            "614路 下一站就是长乐职专站，请准备上车",
            alert(TrackingEngine.AlertKind.APPROACHING, waiting.copy(stopsRemaining = 0, etaSeconds = -1))
        )
    }

    @Test
    fun pinnedBus_namesPlateAndSaysGetOff() {
        val pinned = waiting.copy(pinnedBusId = "b2", pinnedPlate = "闽A06303D")
        assertEquals(
            "614路，车牌闽A06303D 还有2站到长乐职专站，大约5分钟，请准备下车",
            alert(TrackingEngine.AlertKind.APPROACHING, pinned)
        )
        assertEquals("614路，车牌闽A06303D 已到达长乐职专站，请下车", alert(TrackingEngine.AlertKind.ARRIVED, pinned))
    }

    @Test
    fun routeOnBoard_saysGetOff() {
        val onBoard = waiting.copy(legPhase = LegPhase.ON_BOARD)
        assertEquals("614路 已到达长乐职专站，请下车", alert(TrackingEngine.AlertKind.ARRIVED, onBoard))
    }

    @Test
    fun transferAndMissed_haveSentences_finishedIsSilent() {
        assertEquals(
            "已到换乘站长乐职专站，请下车换乘M492",
            AlertSpeech.sentenceFor(TrackingEngine.Effect.TransferAlert("长乐职专站", "M492"))
        )
        assertEquals("614路 已经开走了，正在帮你等下一班", alert(TrackingEngine.AlertKind.MISSED, waiting))
        assertNull(AlertSpeech.sentenceFor(TrackingEngine.Effect.Finished.Completed))
        assertNull(AlertSpeech.sentenceFor(TrackingEngine.Effect.Finished.Boarded(waiting)))
    }
}
