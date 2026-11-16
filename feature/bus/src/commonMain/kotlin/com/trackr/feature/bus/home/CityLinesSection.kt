package com.trackr.feature.bus.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.designsystem.theme.item
import com.trackr.feature.bus.resources.Res as BusRes
import com.trackr.feature.bus.resources.bus_city_lines_count
import com.trackr.feature.bus.resources.bus_city_lines_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 「本城线路」：掌上出行的县级城市线路不多（一个县二三十条），
 * 首页直接铺开，不用先搜。车来了 / 掌上公交列不出全部，这块不出现。
 */
internal fun LazyListScope.cityLinesSection(state: BusHomeUiState, onEvent: (BusHomeEvent) -> Unit) {
    if (state.cityLines.isEmpty() && !state.isLoadingCityLines) return

    item(key = "city_lines_header", contentType = "section_header") {
        SectionHeader(
            title = stringResource(BusRes.string.bus_city_lines_title) +
                " · " + stringResource(BusRes.string.bus_city_lines_count, state.cityLines.size)
        )
    }

    if (state.cityLines.isEmpty()) {
        item(key = "city_lines_loading", contentType = "loading") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(strokeWidth = 3.dp) }
        }
        return
    }

    items(
        items = state.cityLines,
        key = { it.key },
        contentType = { "city_line" }
    ) { line ->
        CityLineRow(line = line, onClick = { onEvent(BusHomeEvent.OpenLine(line.open)) })
    }
}

@Composable
private fun CityLineRow(line: CityLineUi, onClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = dimens.cardPadding, vertical = dimens.spaceM),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = line.lineName,
                    style = MaterialTheme.typography.item,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (line.startStop.isNotBlank() || line.endStop.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (line.isBidirectional) {
                            "${line.startStop} ⇄ ${line.endStop}"
                        } else {
                            "${line.startStop} → ${line.endStop}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = vectorResource(Res.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

