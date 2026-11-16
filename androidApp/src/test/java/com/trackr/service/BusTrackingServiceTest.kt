package com.trackr.service

import android.app.Application
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.domain.platform.Announcer
import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.WatchLine
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.testing.FakeAnnouncer
import com.trackr.core.testing.FakeLocationSource
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.ITransitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * 追踪服务（Robolectric，SDK 34）：前台服务类型、定位开关、会话结束时停服务。
 * 实时数据用 [FakeTransitRepository]，定位用 [FakeLocationSource]，会话用 App 里真实的 [TrackingRepository]。
 */
@RunWith(RobolectricTestRunner::class)
class BusTrackingServiceTest {

    private lateinit var app: TrackrApp
    private lateinit var location: FakeLocationSource
    private lateinit var transit: FakeTransitRepository
    private val trackingRepository: TrackingRepository get() = GlobalContext.get().get()
    private val notificationManager: NotificationManager
        get() = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        location = FakeLocationSource(precisePermission = true)
        transit = FakeTransitRepository()
        loadKoinModules(
            module {
                single<LocationSource> { location }
                single<ITransitRepository> { transit }
            }
        )
    }

    // ---------------------------------------------------------------- 样例数据

    private fun lineDetail(lineId: String, buses: List<LiveBus>) = BusLineDetail(
        line = BusLineDirection(
            lineId = lineId,
            name = "59路",
            lineNo = "59",
            direction = 0,
            startStop = "始发",
            endStop = "终点",
            firstTime = "06:00",
            lastTime = "22:00"
        ),
        stops = (1..10).map { BusStop(stopId = "s$it", name = "站$it", order = it, wgsLat = 25.0 + it * 0.001, wgsLng = 119.0) },
        buses = buses
    )

    private fun routeTarget() = TrackingTarget.Route(
        group = TransitGroup(
            id = "g1",
            name = "上班",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "站3",
                    toStopName = "站7",
                    toStopId = "s7",
                    candidateLines = listOf(
                        CandidateLine(cityId = "034", lineId = "L59", lineName = "59路", fromStopOrder = 3, toStopOrder = 7)
                    )
                )
            )
        )
    )

    private fun singleTarget() = TrackingTarget.SingleLine(
        cityId = "034",
        lineId = "L59",
        lineName = "59路",
        targetStopId = "s7",
        targetStopName = "站7",
        targetOrder = 7,
        totalStops = 10
    )

    private fun startService(sessionId: String): ServiceController<BusTrackingService> =
        Robolectric.buildService(BusTrackingService::class.java, BusTrackingService.startIntent(app, sessionId))
            .create()
            .startCommand(0, 1)

    /** 主线程消息跑完，等 IO 线程的网络回调回到主线程 */
    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("等待超时：$message")
            Thread.sleep(20)
        }
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun route_withPrecisePermission_usesLocationTypeAndTurnsLocationOnNearBoardStop() {
        // 车在第 2 站、离上车站 A（第 3 站）1 站：等车近站档位要开定位
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(routeTarget()))
        val service = startService(session.sessionId).get()

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.lastForegroundServiceType
        )
        assertNotNull(shadowOf(service).lastForegroundNotification)

        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }
        assertEquals(LegPhase.WAITING_BOARD, trackingRepository.legState.value.legPhase)
        assertEquals(LocationCadence.Tier.WAITING_NEAR, service.currentLocationTier)
        assertEquals(listOf(LocationCadence.Tier.WAITING_NEAR), location.requestedTiers)
        assertEquals(1, location.activeCollectors)
    }

    @Test
    fun route_withoutPrecisePermission_fallsBackToSpecialUseAndNoLocation() {
        location.precisePermission = false
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(routeTarget()))
        val service = startService(session.sessionId).get()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.lastForegroundServiceType)
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }
        assertEquals(LocationCadence.Tier.OFF, service.currentLocationTier)
        assertTrue(location.requestedTiers.isEmpty())
        assertEquals(0, location.activeCollectors)
    }

    @Test
    fun singleLine_takesLocationType_withAutoStopAfterBoarding() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        // 默认开着「上车后自动结束追踪」：单线路也要定位（判人有没有跟着车走），前台就得带 location 类型
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val service = startService(session.sessionId).get()

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.lastForegroundServiceType
        )
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }
        assertEquals(LegPhase.SINGLE, trackingRepository.legState.value.legPhase)
        // 车还有 1 站：这时候开始取定位，车到站后才判得出人有没有跟着走
        assertEquals(LocationCadence.Tier.WAITING_NEAR, service.currentLocationTier)
        assertEquals(listOf(LocationCadence.Tier.WAITING_NEAR), location.requestedTiers)
    }

    @Test
    fun singleLine_autoStopOff_neverAsksForLocationType() {
        transit.settings.setAutoStopAfterBoarding(false)
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val service = startService(session.sessionId).get()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.lastForegroundServiceType)
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }
        assertEquals(LegPhase.SINGLE, trackingRepository.legState.value.legPhase)
        assertEquals(LocationCadence.Tier.OFF, service.currentLocationTier)
        assertTrue(location.requestedTiers.isEmpty())
    }

    @Test
    fun stop_turnsLocationOffAndStopsService() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(routeTarget()))
        val service = startService(session.sessionId).get()
        waitUntil("定位打开") { location.activeCollectors == 1 }

        // 通知「结束」按钮 / 首页结束追踪都是清掉仓库里的会话
        trackingRepository.stop()
        waitUntil("服务停下") { shadowOf(service).isStoppedBySelf }

        assertEquals(LocationCadence.Tier.OFF, service.currentLocationTier)
        assertEquals(0, location.activeCollectors)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNull(trackingRepository.session.value)
    }

    /**
     * #28：结束追踪以后，这一项留下的到站提醒也要跟着消失，不能继续挂在通知栏和锁屏上等用户手动点掉。
     */
    @Test
    fun stop_cancelsArrivalAlertOfThatSession() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(routeTarget()))
        val service = startService(session.sessionId).get()
        waitUntil("定位打开") { location.activeCollectors == 1 }

        // 到站那一刻服务会发这条；这里直接替它发出来，然后结束追踪
        val alerts = GlobalContext.get().get<ArrivalAlertNotifier>()
        alerts.triggerArrivedAlert(
            app,
            trackingRepository.legState.value,
            forceStandalone = true,
            sessionId = session.sessionId
        )
        val arrivedId = ArrivalAlertNotifier.alertNotificationId(
            ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED,
            session.sessionId
        )
        assertNotNull("到站提醒应先发出来", shadowOf(notificationManager).getNotification(arrivedId))

        trackingRepository.stop()
        waitUntil("服务停下") { shadowOf(service).isStoppedBySelf }

        assertNull(
            "追踪结束后提醒不该留着",
            shadowOf(notificationManager).getNotification(arrivedId)
        )
    }

    /** issue #52：只追一辆车到下车站，开着「语音播报提醒」时到站会念出来，念完这次追踪结束 */
    @Test
    fun pinnedBus_arrives_speaksAlertWhenVoiceOn_andFinishes() {
        val announcer = FakeAnnouncer()
        loadKoinModules(module { single<Announcer> { announcer } })
        transit.settings.setVoiceAlerts(true)
        transit.realtime.lineDetailResult = Result.success(
            lineDetail(
                "L59",
                listOf(
                    // 前车先到站：不是指定的那辆，不提醒
                    LiveBus(busId = "ahead", plateOrFleetNo = "闽B1", order = 7, segmentProgress = 1f, isArrived = true),
                    LiveBus(busId = "mine", plateOrFleetNo = "闽B2", order = 8, segmentProgress = 0.2f)
                )
            )
        )
        val session = requireNotNull(
            trackingRepository.start(singleTarget().copy(pinnedBusId = "mine", pinnedPlate = "闽B2"))
        )
        val service = startService(session.sessionId).get()

        waitUntil("念出到站提醒") { announcer.spoken.isNotEmpty() }
        assertEquals("59路，车牌闽B2 已到达站7，请下车", announcer.spoken.single())
        waitUntil("指定的车开过下车站后追踪结束") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun voiceOff_alertIsNotSpoken() {
        val announcer = FakeAnnouncer()
        loadKoinModules(module { single<Announcer> { announcer } })
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 7, segmentProgress = 1f, isArrived = true)))
        )
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        startService(session.sessionId).get()
        waitUntil("轮询过一轮") { trackingRepository.sessionById(session.sessionId)?.state?.hasTriggeredArrivedAlert == true }

        assertTrue(announcer.spoken.isEmpty())
    }

    @Test
    fun staleStartRequest_entersForegroundThenStops() {
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        // 启动请求还没到，用户又点了结束
        trackingRepository.stop()
        val service = startService(session.sessionId).get()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    // ---------------------------------------------------------------- 一起等（第 15 步）

    private fun watchTarget() = TrackingTarget.WatchTogether(
        cityId = "034",
        stopId = "s5",
        stopName = "站5",
        lines = listOf(
            WatchLine(lineId = "L59", lineName = "59路", stopOrder = 5),
            WatchLine(lineId = "L60", lineName = "60路", stopOrder = 5),
            WatchLine(lineId = "L61", lineName = "61路", stopOrder = 5)
        )
    )

    private fun stopStatus(lineId: String, buses: List<LiveBus>) = NearbyStopLineStatus(
        line = BusLineDirection(lineId = lineId, name = lineId, startStop = "始发", endStop = "终点"),
        targetOrder = 5,
        targetStopId = "s5",
        targetStopName = "站5",
        approachingBuses = buses
    )

    private fun stopWithLines() = NearbyStop(
        stopId = "s5",
        name = "站5",
        wgsLat = 25.005,
        wgsLng = 119.0,
        lines = listOf(
            stopStatus("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, etaSeconds = 600))),
            stopStatus("L60", listOf(LiveBus(busId = "b2", plateOrFleetNo = "闽B2", order = 4, etaSeconds = 120))),
            stopStatus("L61", listOf(LiveBus(busId = "b3", plateOrFleetNo = "闽B3", order = 3, etaSeconds = 300)))
        )
    )

    @Test
    fun watchTogether_onePollIsOneStopRequest_fastestShown_usesLocationType() {
        transit.realtime.stopDetailResult = Result.success(listOf(stopWithLines()))
        val session = requireNotNull(trackingRepository.start(watchTarget()))
        val service = startService(session.sessionId).get()

        // Q17：一起等也要 location 类型
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.lastForegroundServiceType
        )
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b2" }

        val polled = requireNotNull(trackingRepository.session.value)
        assertEquals("L60", polled.leg.lineId)
        assertEquals(listOf("L60", "L61", "L59"), polled.watchedLines.map { it.line.lineId })
        // 3 条线只发 1 次站点请求，不请求线路接口
        assertEquals(1, transit.realtime.stopDetailCallCount)
        assertTrue(transit.realtime.lineDetailCalls.isEmpty())
        // 最先到的离站 1 站：开等车近站档定位
        assertEquals(LocationCadence.Tier.WAITING_NEAR, service.currentLocationTier)
    }

    @Test
    fun watchTogether_eachLineHasItsOwnNotification_andStopRemovesOnlyThatLine() {
        transit.realtime.stopDetailResult = Result.success(listOf(stopWithLines()))
        val session = requireNotNull(trackingRepository.start(watchTarget()))
        startService(session.sessionId).get()
        waitUntil("三条线都写回") { trackingRepository.session.value?.watchedLines?.size == 3 }

        // 一起等不是合成一条：每条线一条常驻通知，各自有进度
        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        val ids = listOf("L59", "L60", "L61").map {
            notifier.notificationIdOf(LiveProgressNotifier.watchLineKey(session.sessionId, it))
        }
        ids.forEach { id ->
            assertNotNull("每条线一条通知", shadowOf(notificationManager).getNotification(id))
        }
        assertNull(
            "不能又合成一条总通知",
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(session.sessionId))
        )
        // 每条通知写的是自己那条线
        assertEquals(
            "59路",
            requireNotNull(shadowOf(notificationManager).getNotification(ids[0]))
                .extras.getCharSequence("android.title").toString().substringBefore(" "),
        )

        // 点 59 路那条的「结束」：只去掉这条线，另外两条的通知还在
        val n59 = requireNotNull(shadowOf(notificationManager).getNotification(ids[0]))
        val stopButton = n59.actions.first { it.title.toString().contains("结束") }
        app.sendBroadcast(shadowOf(stopButton.actionIntent).savedIntent)

        waitUntil("59 路被去掉") {
            trackingRepository.session.value?.watchedLines?.none { it.line.lineId == "L59" } == true
        }
        waitUntil("59 路通知撤掉") { shadowOf(notificationManager).getNotification(ids[0]) == null }
        assertNotNull(shadowOf(notificationManager).getNotification(ids[1]))
        assertNotNull(shadowOf(notificationManager).getNotification(ids[2]))
        assertEquals(listOf("L60", "L61"), trackingRepository.session.value?.watchedLines?.map { it.line.lineId })
    }

    @Test
    fun watchTogether_stoppingForegroundLinesOneByOne_removesEveryNotification() {
        transit.realtime.stopDetailResult = Result.success(listOf(stopWithLines()))
        val session = requireNotNull(trackingRepository.start(watchTarget()))
        val service = startService(session.sessionId).get()
        waitUntil("最快的 60 路排在第一条") {
            trackingRepository.session.value?.watchedLines?.firstOrNull()?.line?.lineId == "L60"
        }

        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        listOf("L60", "L61", "L59").forEachIndexed { index, lineId ->
            val id = notifier.notificationIdOf(LiveProgressNotifier.watchLineKey(session.sessionId, lineId))
            assertEquals(id, shadowOf(service).lastForegroundNotificationId)
            val notification = requireNotNull(shadowOf(notificationManager).getNotification(id))
            notification.actions.first { it.title.toString().contains("结束") }.actionIntent.send()
            waitUntil("$lineId 的通知撤掉") { shadowOf(notificationManager).getNotification(id) == null }
            assertEquals(2 - index, trackingRepository.session.value?.watchedLines?.size ?: 0)
            if (index < 2) assertFalse(shadowOf(service).isStoppedBySelf)
        }
        waitUntil("最后一条结束后服务停下") { shadowOf(service).isStoppedBySelf }
        assertTrue(notificationManager.activeNotifications.none {
            it.notification.channelId == LiveProgressNotifier.CHANNEL_ID_LIVE_PROGRESS
        })
    }

    @Test
    fun watchTogether_afterBoarding_pollsOnlyThatLine() {
        transit.realtime.stopDetailResult = Result.success(listOf(stopWithLines()))
        transit.realtime.lineDetailsMap["L60"] = lineDetail(
            "L60",
            listOf(LiveBus(busId = "b2", plateOrFleetNo = "闽B2", order = 6, segmentProgress = 0.2f, wgsLat = 25.006, wgsLng = 119.0))
        )
        val session = requireNotNull(trackingRepository.start(watchTarget()))
        startService(session.sessionId).get()
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b2" }

        // 通知「我上车了」：没有车在站，按排第一的 60 路上车，先不锁车；服务马上只请求 60 路
        trackingRepository.confirmBoarded()
        waitUntil("上车后按 60 路轮询并补锁") { trackingRepository.legState.value.lockedBusId == "b2" }

        val riding = trackingRepository.legState.value
        assertEquals(LegPhase.ON_BOARD, riding.legPhase)
        assertEquals("L60", riding.lineId)
        assertEquals(10, riding.targetOrder) // 没有下车站：目标是终点站
        assertEquals(listOf("L60"), transit.realtime.lineDetailCalls)
        assertEquals(1, transit.realtime.stopDetailCallCount)
    }

    // ---------------------------------------------------------------- 起步失败（进不了前台）

    @Test
    fun startFailure_normalSession_endsItAndNotifiesUser() {
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val service = Robolectric.buildService(BusTrackingService::class.java).create().get()

        // start() 已经返回 true，服务却起不来：没人轮询，会话留着只会让界面一直显示「追踪中」
        service.endStartFailure(session, SecurityException("fgs denied"))

        assertNull("正常会话起不了服务就该结束，不能留在仓库里", trackingRepository.session.value)
        assertFalse(trackingRepository.legState.value.isActive)
        assertNotNull(
            "要告诉用户追踪没起来",
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_INTERRUPTED)
        )
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun startFailure_interruptedSession_keepsItForNextOpen() {
        // 进程重建读回的中断会话：用户还没决定要不要继续，不能替他结束。
        // 仓库里的会话仍是活动状态，所以这里断言的是「没被 stop 掉」。
        val stored = requireNotNull(trackingRepository.start(singleTarget()))
        val service = Robolectric.buildService(BusTrackingService::class.java).create().get()

        service.endStartFailure(stored.copy(interrupted = true), SecurityException("fgs denied"))

        assertEquals(
            "中断会话要留给下次打开 App 再接续，不能在这里结束",
            stored.sessionId,
            trackingRepository.session.value?.sessionId
        )
        assertNotNull(
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_INTERRUPTED)
        )
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun destroy_endsTrackedSession() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val controller = startService(session.sessionId)
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }

        controller.destroy()

        assertNull(trackingRepository.session.value)
        assertFalse(trackingRepository.legState.value.isActive)
    }

    // ---------------------------------------------------------------- 同时追多项（第 36 步）

    @Test
    fun completedForegroundRoute_cleansUpNotificationsAndKeepsOtherLineRunning() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 7, segmentProgress = 1f, isArrived = true)))
        )
        val first = requireNotNull(trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路")))
        val service = startService(first.sessionId).get()
        waitUntil("第一项写回") { trackingRepository.sessionById(first.sessionId)?.state?.currentBusId == "b1" }

        val route = requireNotNull(trackingRepository.start(routeTarget()))
        trackingRepository.update(route.sessionId, route.generation) {
            it.copy(state = it.state.copy(legPhase = LegPhase.ON_BOARD, lockedBusId = "b1"))
        }
        service.onStartCommand(BusTrackingService.startIntent(app, route.sessionId), 0, 2)
        waitUntil("路线到终点后自动结束") { trackingRepository.sessionById(route.sessionId) == null }

        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        waitUntil("前台交回仍在追踪的线路") {
            shadowOf(service).lastForegroundNotificationId == notifier.notificationIdOf(first.sessionId)
        }
        assertNotNull(shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId)))
        assertNull(shadowOf(notificationManager).getNotification(notifier.notificationIdOf(route.sessionId)))
        assertNull(
            "自动结束也必须经过统一清理，不能在收集会话变化前丢掉 worker",
            shadowOf(notificationManager).getNotification(
                ArrivalAlertNotifier.alertNotificationId(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED, route.sessionId)
            )
        )
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun twoLines_arePolledTogether_eachHasItsOwnNotification() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val first = requireNotNull(trackingRepository.start(singleTarget()))
        val service = startService(first.sessionId).get()
        waitUntil("第一项写回") { trackingRepository.sessionById(first.sessionId)?.state?.currentBusId == "b1" }

        // 同一个站再追另一条线：不覆盖前一项，两路各poll各的
        val second = requireNotNull(
            trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路"))
        )
        waitUntil("第二项写回") { trackingRepository.sessionById(second.sessionId)?.state?.currentBusId == "b1" }

        assertEquals(listOf(first.sessionId, second.sessionId), trackingRepository.sessions.value.map { it.sessionId })
        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        assertNotNull(
            "每项一条常驻通知",
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId))
        )
        assertNotNull(
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId))
        )

        // 应用里结束第一项：只撤它那条通知，第二项还在追、通知也在，服务不退出
        trackingRepository.stop(first.sessionId)
        waitUntil("第一项通知撤掉") {
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId)) == null
        }
        assertEquals(listOf(second.sessionId), trackingRepository.sessions.value.map { it.sessionId })
        assertNotNull(
            "第二项的通知不能跟着消失",
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId))
        )
        assertFalse("还有一项在追，服务不能停", shadowOf(service).isStoppedBySelf)

        // 全结束才退出前台，通知一条不剩
        trackingRepository.stop()
        waitUntil("服务停下") { shadowOf(service).isStoppedBySelf }
        assertTrue(shadowOf(service).isForegroundStopped)
        waitUntil("两条通知都撤掉") {
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId)) == null &&
                shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId)) == null
        }
    }

    // ---------------------------------------------------------------- 前台服务不丢 location（issue #49）

    /** 像真机那样：App 在后台时 startForeground 带 location 类型会被拒绝 */
    private fun BusTrackingService.rejectLocationLikeBackground(): (Int, android.app.Notification, Int) -> Unit {
        val real = foregroundStarter
        foregroundStarter = { id, notification, type ->
            if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) throw SecurityException("后台不能带 location")
            real(id, notification, type)
        }
        return real
    }

    private val withLocation =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    private fun notificationText(id: Int, key: String): String? =
        shadowOf(notificationManager).getNotification(id)?.extras?.getCharSequence(key)?.toString()

    @Test
    fun newTrackingStartedInBackground_keepsLocationTypeOnExistingForeground() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val first = requireNotNull(trackingRepository.start(singleTarget()))
        val service = startService(first.sessionId).get()
        waitUntil("第一项写回") { trackingRepository.sessionById(first.sessionId)?.state?.currentBusId == "b1" }
        assertEquals(withLocation, service.lastForegroundServiceType)

        // 定时提醒在后台又开了一项：挪前台通知会被拒绝带 location——以前退回 specialUse，两项都判不了上车
        service.rejectLocationLikeBackground()
        val second = requireNotNull(trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路")))
        waitUntil("第二项写回") { trackingRepository.sessionById(second.sessionId)?.state?.currentBusId == "b1" }

        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        assertEquals(withLocation, service.lastForegroundServiceType)
        assertEquals("前台留在第一项那条通知上", notifier.notificationIdOf(first.sessionId), shadowOf(service).lastForegroundNotificationId)
        assertNotNull(shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId)))
        assertTrue(service.currentLocationTier != LocationCadence.Tier.OFF)
        assertNull("定位没丢，不提示", notificationText(notifier.notificationIdOf(second.sessionId), "android.subText")
            ?.takeIf { it == BusTrackingService.BOARDING_NOTICE_OPEN_APP })
    }

    @Test
    fun foregroundItemEndsInBackground_nextItemTakesOverItsNotification_thenMovesBackWhenAppOpens() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        val first = requireNotNull(trackingRepository.start(singleTarget()))
        val service = startService(first.sessionId).get()
        waitUntil("第一项写回") { trackingRepository.sessionById(first.sessionId)?.state?.currentBusId == "b1" }
        val second = requireNotNull(trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路")))
        waitUntil("第二项写回") { trackingRepository.sessionById(second.sessionId)?.state?.currentBusId == "b1" }
        val firstId = notifier.notificationIdOf(first.sessionId)
        val secondId = notifier.notificationIdOf(second.sessionId)
        assertEquals("前台在最新的一项上", secondId, shadowOf(service).lastForegroundNotificationId)

        // App 在后台时第二项（前台那项）结束：挪不动，第一项画在原来那条前台通知上，location 不丢
        val real = service.rejectLocationLikeBackground()
        trackingRepository.stop(second.sessionId)
        waitUntil("第一项接替前台通知") { notificationText(secondId, "android.title")?.contains("59路") == true }
        assertNull("第一项原来那条撤掉，不重复", shadowOf(notificationManager).getNotification(firstId))
        assertEquals(withLocation, service.lastForegroundServiceType)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // 打开 App：挪回第一项自己的通知
        service.foregroundStarter = real
        service.onAppForeground()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(firstId, shadowOf(service).lastForegroundNotificationId)
        assertEquals(withLocation, service.lastForegroundServiceType)
        assertNotNull(shadowOf(notificationManager).getNotification(firstId))
        assertNull("接替用的那条撤掉", shadowOf(notificationManager).getNotification(secondId))
    }

    @Test
    fun startedInBackgroundWithoutLocation_showsNotice_appForegroundRestoresIt() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        // 定时提醒在后台开始追踪：服务一创建就进前台（会话收集在 onCreate 里），所以要在 create 之前换上
        val controller = Robolectric.buildService(BusTrackingService::class.java, BusTrackingService.startIntent(app, session.sessionId))
        val service = controller.get()
        val real = service.rejectLocationLikeBackground()
        controller.create().startCommand(0, 1)
        waitUntil("写回") { trackingRepository.sessionById(session.sessionId)?.state?.currentBusId == "b1" }

        val id = notifier.notificationIdOf(session.sessionId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.lastForegroundServiceType)
        assertEquals(BusTrackingService.BOARDING_NOTICE_OPEN_APP, notificationText(id, "android.subText"))
        assertEquals(LocationCadence.Tier.OFF, service.currentLocationTier)

        service.foregroundStarter = real
        service.onAppForeground()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(withLocation, service.lastForegroundServiceType)
        assertTrue(notificationText(id, "android.subText") != BusTrackingService.BOARDING_NOTICE_OPEN_APP)
        assertEquals(LocationCadence.Tier.WAITING_NEAR, service.currentLocationTier)
    }

    @Test
    fun stopFromNotification_onlyEndsThatLine() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val first = requireNotNull(trackingRepository.start(singleTarget()))
        startService(first.sessionId)
        waitUntil("第一项写回") { trackingRepository.sessionById(first.sessionId)?.state?.currentBusId == "b1" }
        val second = requireNotNull(
            trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路"))
        )
        waitUntil("第二项写回") { trackingRepository.sessionById(second.sessionId)?.state?.currentBusId == "b1" }

        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        val notification = requireNotNull(
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId))
        )
        val stopButton = notification.actions.first { it.title.toString().contains("结束") }
        app.sendBroadcast(shadowOf(stopButton.actionIntent).savedIntent)

        // 只结束点的那一项（以前会一起结束，剩下那条点「结束」又什么都做不了，通知也关不掉）
        waitUntil("只结束第一项") {
            trackingRepository.sessions.value.map { it.sessionId } == listOf(second.sessionId)
        }
        waitUntil("第一项通知撤掉") {
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId)) == null
        }
        assertNotNull(
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId))
        )
    }

    @Test
    fun stopFromLegacyNotification_withoutSessionId_onlyEndsPrimary() {
        val first = requireNotNull(trackingRepository.start(singleTarget()))
        startService(first.sessionId)
        val second = requireNotNull(
            trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路"))
        )
        waitUntil("两项都在") { trackingRepository.sessions.value.size == 2 }

        // 升级前发出去的通知按钮不带 sessionId：只作用在主会话，不能把全部追踪停掉
        app.sendBroadcast(
            android.content.Intent(app, TrackingActionReceiver::class.java)
                .setAction(TrackingActionReceiver.ACTION_STOP_TRACKING)
        )
        waitUntil("只结束主会话") {
            trackingRepository.sessions.value.map { it.sessionId } == listOf(second.sessionId)
        }
    }
}
