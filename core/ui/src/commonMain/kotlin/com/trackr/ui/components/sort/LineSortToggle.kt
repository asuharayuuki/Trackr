package com.trackr.ui.components.sort

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_arrow_drop_down
import com.trackr.core.ui.resources.Res as UiRes
import com.trackr.core.ui.resources.ui_sort_by_arrival
import com.trackr.core.ui.resources.ui_sort_default
import com.trackr.ui.icons.TrackrIcons
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 测试用 tag */
object LineSortToggleTags {
    const val TRIGGER = "line_sort_trigger"
    const val DEFAULT = "line_sort_default"
    const val BY_ARRIVAL = "line_sort_by_arrival"
}

/**
 * 线路排序切换（issue #56）：小字「默认顺序 ▾」，点开下拉菜单选「默认顺序」/「先到的在前」，当前项打勾。
 * 站点页线路列表上方和首页「附近」标题旁共用；开关存在 `SettingsRepository.sortLinesByArrival`，两处切的是同一个。
 */
@Composable
fun LineSortToggle(
    sortByArrival: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(UiRes.string.ui_sort_default)
    val byArrivalLabel = stringResource(UiRes.string.ui_sort_by_arrival)
    Box(modifier = modifier) {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(start = 8.dp, end = 2.dp),
            modifier = Modifier.testTag(LineSortToggleTags.TRIGGER)
        ) {
            Text(
                text = if (sortByArrival) byArrivalLabel else defaultLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOption(defaultLabel, selected = !sortByArrival, tag = LineSortToggleTags.DEFAULT) {
                expanded = false
                if (sortByArrival) onChange(false)
            }
            SortOption(byArrivalLabel, selected = sortByArrival, tag = LineSortToggleTags.BY_ARRIVAL) {
                expanded = false
                if (!sortByArrival) onChange(true)
            }
        }
    }
}

@Composable
private fun SortOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(TrackrIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        modifier = Modifier.testTag(tag)
    )
}
