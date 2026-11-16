package com.trackr.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.result.rememberResultEventBus
import androidx.navigation3.runtime.result.rememberResultEventBusNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.trackr.core.navigation.ElderNavigator
import com.trackr.core.navigation.ElderRoutes
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.TrackrTransitions
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.navigation.rememberElderBackStack
import com.trackr.core.navigation.scene.BottomSheetSceneStrategy
import com.trackr.feature.bus.elder.ElderArrivalAlertHost
import com.trackr.feature.bus.elderBusEntries
import com.trackr.feature.bus.home.BusHomeViewModel
import com.trackr.feature.line.elderLineEntries
import com.trackr.feature.me.meEntries
import com.trackr.feature.route.routeEntries
import com.trackr.feature.search.elderSearchEntries
import com.trackr.feature.stop.elderStopEntries
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import com.trackr.ui.navigation.LocalAppViewModelStoreOwner
import org.koin.compose.viewmodel.koinViewModel

/** 长辈版不支持的页面被打开时的提示（§8.4）。点「知道了」才关，不会自己消失 */
internal const val ELDER_UNSUPPORTED_NOTICE = "长辈版不支持此功能，可在设置里切回标准版"

/**
 * 长辈版的导航（§8.4，第 31 步）：一条栈、没有底栏和地图规划，根页是首页；「我的」从首页右上角「设置」进。
 * 首页、搜索、线路、站点是各 feature 的长辈版页面（见 [elderEntries]），路线详情和「我的」用标准版的；Key 和标准版一样。
 *
 * Deep link 和标准版用同一个解析结果，由 [ElderNavigator.applyDeepLink] 落成 `[首页, …]`。
 */
@Composable
internal fun ElderNavApp(
    deepLink: DeepLink?,
    onDeepLinkConsumed: () -> Unit,
) {
    val stack = rememberElderBackStack()
    val navigator = remember(stack) { ElderNavigator(stack) }

    // 和标准版一样先取 Activity 级的首页 ViewModel；恢复到子页时首页 Route 不组合，先关掉它默认开着的轮询
    val homeViewModel = koinViewModel<BusHomeViewModel>(viewModelStoreOwner = LocalAppViewModelStoreOwner.current)
    LaunchedEffect(homeViewModel) {
        if (navigator.currentKey != ElderRoutes.root) homeViewModel.setPollingActive(false)
    }

    // 小组件 / 快捷方式 / 通知的跳转（§3.6）：一次性消费；冷启动直接落到子页时补关首页轮询（同标准版）
    val coldStartLink = remember { deepLink }
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        when (link) {
            is DeepLink.Stack -> {
                navigator.applyDeepLink(link)
                if (link === coldStartLink && navigator.currentKey != ElderRoutes.root) {
                    homeViewModel.setPollingActive(false)
                }
            }
            // 「上了哪辆？」：弹层叠在当前栈顶
            is DeepLink.ChooseBoardedBus -> navigator.open(ScreenKey.ChooseBoardedBus(link.sessionId))
            // 乘车码由平台层直接拉起，不会传到这里
            is DeepLink.TransitCode -> Unit
        }
        onDeepLinkConsumed()
    }

    val resultEventBus = rememberResultEventBus()
    val entries = rememberDecoratedNavEntries(
        backStack = stack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
            rememberResultEventBusNavEntryDecorator(resultEventBus),
        ),
        entryProvider = entryProvider { elderEntries(navigator) },
    )
    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            entries = entries,
            // 单栈：首页按返回交给系统退出
            onBack = { navigator.back() },
            modifier = Modifier.fillMaxSize(),
            sceneStrategies = remember { listOf(BottomSheetSceneStrategy<NavKey>()) },
            transitionSpec = TrackrTransitions.push,
            popTransitionSpec = TrackrTransitions.pop,
            predictivePopTransitionSpec = TrackrTransitions.predictivePop
        )
        // 车到站（单线路「车快到时叫我」）：App 在前台时整屏变绿，盖在哪一页上都行（第 34 步）
        ElderArrivalAlertHost()
    }

    // 打开了长辈版不支持的页面：常驻提示，点「知道了」才关（第 31 步留的对话框换成 UserMessageHost）
    UserMessageHost(
        message = if (navigator.unsupportedNotice) HostMessage(key = ELDER_UNSUPPORTED_NOTICE, text = ELDER_UNSUPPORTED_NOTICE) else null,
        onResult = { _, _ -> navigator.dismissUnsupportedNotice() },
        persistent = true
    )
}

/**
 * 长辈版能打开的页面（§8.4）：长辈版首页（第 32 步，右上角「设置」）、长辈版搜索（第 34 步）、长辈版线路页（第 32 步）、长辈版站点页（第 34 步）和「上了哪辆？」、路线详情、「我的」及其子页。
 * 规划、编辑收藏、编辑提醒不支持，[ElderNavigator] 不会把它们压栈；`routeEntries` 里的规划页 entry 用不到。
 */
private fun EntryProviderScope<NavKey>.elderEntries(navigator: ElderNavigator) {
    elderBusEntries(navigator)
    elderSearchEntries(navigator)
    elderLineEntries(navigator)
    elderStopEntries(navigator)
    routeEntries(navigator)
    meEntries(navigator)
}
