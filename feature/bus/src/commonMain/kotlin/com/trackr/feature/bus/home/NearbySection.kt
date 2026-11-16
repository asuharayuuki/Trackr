package com.trackr.feature.bus.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_relocate
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.designsystem.theme.item
import com.trackr.feature.bus.resources.Res as BusRes
import com.trackr.feature.bus.resources.bus_nearby_empty
import com.trackr.feature.bus.resources.bus_nearby_loading
import com.trackr.feature.bus.resources.bus_nearby_stop_detail
import com.trackr.feature.bus.resources.bus_nearby_title
import com.trackr.ui.components.arrival.LineBadge
import com.trackr.ui.components.arrival.arrivalToneColor
import com.trackr.ui.components.sort.LineSortToggle
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 「附近」：固定 6 个站（v3），每个站一张卡，站名一行点进站点页，下面每条线一行点进线路页。
 * 卡里的线不多，用 Column 不用 Lazy（§4.3）。
 */
internal fun LazyListScope.nearbySection(state: BusHomeUiState, onEvent: (BusHomeEvent) -> Unit) {
    item(key = "nearby_header", contentType = "section_header") {
        SectionHeader(
            title = stringResource(BusRes.string.bus_nearby_title),
            modifier = Modifier.testTag(BusHomeTags.NEARBY_HEADER),
            trailing = if (state.nearbyCards.isNotEmpty()) {
                {
                    LineSortToggle(
                        sortByArrival = state.sortNearbyByArrival,
                        onChange = { onEvent(BusHomeEvent.SetNearbySortByArrival(it)) }
                    )
                }
            } else {
                null
            }
        )
    }

    when {
        state.nearbyCards.isEmpty() && state.isBusy -> item(key = "nearby_loading", contentType = "loading") {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 3.dp)
            }
        }
        state.nearbyCards.isEmpty() -> item(key = "nearby_empty", contentType = "empty_hint") {
            EmptyHint(text = stringResource(BusRes.string.bus_nearby_empty)) {
                TextButton(onClick = { onEvent(BusHomeEvent.RetryLocation) }) { Text(stringResource(Res.string.action_relocate)) }
            }
        }
        else -> items(
            items = state.nearbyCards,
            key = { it.key },
            contentType = { "nearby_stop" }
        ) { stop ->
            NearbyStopCard(
                stop = stop,
                onOpenStop = { onEvent(BusHomeEvent.OpenStop(stop.open)) },
                onOpenLine = { line -> onEvent(BusHomeEvent.OpenLine(line.open)) }
            )
        }
    }
}

@Composable
private fun NearbyStopCard(
    stop: NearbyStopUi,
    onOpenStop: () -> Unit,
    onOpenLine: (NearbyLineUi) -> Unit
) {
    val dimens = TrackrTheme.dimens
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding)
            .testTag(BusHomeTags.nearbyStop(stop.key))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenStop)
                    .padding(start = dimens.cardPadding, end = dimens.spaceS, top = dimens.spaceM, bottom = dimens.spaceS),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stop.name, style = MaterialTheme.typography.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (stop.meta.isNotBlank()) {
                        Text(
                            text = stop.meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_chevron_right),
                    contentDescription = stringResource(BusRes.string.bus_nearby_stop_detail),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (stop.lines.isEmpty()) {
                Text(
                    text = stringResource(BusRes.string.bus_nearby_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = dimens.cardPadding, bottom = dimens.spaceM)
                )
            } else {
                stop.lines.forEach { line ->
                    NearbyLineRow(line = line, onClick = { onOpenLine(line) })
                }
                Spacer(modifier = Modifier.padding(bottom = dimens.spaceXs))
            }
        }
    }
}

/** 上下两行：「325 → 科技园」一行，「4 分钟 · 2 站」一行（右对齐）。
 *  线路名可能很长（如「盘龙大观园河西旅游专线」），挤一行会把终点方向压没；
 *  名字在自己的格子里省略，终点和到站信息都留得住。 */
@Composable
private fun NearbyLineRow(line: NearbyLineUi, onClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.cardPadding, vertical = dimens.spaceS)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LineBadge(name = line.lineName, modifier = Modifier.widthIn(max = 200.dp))
            Spacer(modifier = Modifier.width(dimens.spaceS))
            Text(
                text = line.direction,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(dimens.spaceXs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = line.arrival.big,
                style = MaterialTheme.typography.item,
                color = arrivalToneColor(line.arrival.tone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (line.arrival.small.isNotBlank()) {
                Spacer(modifier = Modifier.width(dimens.spaceS))
                Text(
                    text = line.arrival.small,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
