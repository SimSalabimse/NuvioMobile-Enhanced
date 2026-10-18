package com.nuvio.app.features.home.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.downloads.SmartDownloadSettings
import com.nuvio.app.features.stats.formatWatchHours
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import com.nuvio.app.features.tracking.WatchProgressSource

internal val TONIGHT_CHOICES_MINUTES = listOf(30, 60, 90, 120)

/** Picks what fits in [budgetMs]: Finish-a-series goals first, then Continue Watching order. */
internal fun planTonight(
    items: List<ContinueWatchingItem>,
    budgetMs: Long,
    isGoal: (ContinueWatchingItem) -> Boolean,
): List<ContinueWatchingItem> {
    var left = budgetMs
    val plan = mutableListOf<ContinueWatchingItem>()
    for (item in items.filterNot { it.isReleaseAlert }.sortedByDescending(isGoal)) {
        val need = remainingMs(item)
        if (need <= left) {
            plan += item
            left -= need
        }
    }
    return plan
}

internal fun remainingMs(item: ContinueWatchingItem): Long =
    if (item.durationMs > 0L) (item.durationMs - item.resumePositionMs).coerceAtLeast(0L) else GUESSED_RUNTIME_MS

private const val GUESSED_RUNTIME_MS = 45 * 60_000L

private fun tonightLabel(minutes: Int): String =
    if (minutes % 60 == 0) "${minutes / 60}h" else if (minutes > 60) "${minutes / 60}h${minutes % 60}" else "${minutes}m"

@Composable
internal fun HomeUpNextTonightSection(
    items: List<ContinueWatchingItem>,
    style: ContinueWatchingSectionStyle,
    dataSourceKey: WatchProgressSource,
    sectionPadding: Dp,
    layout: ContinueWatchingLayout,
    modifier: Modifier = Modifier,
    onItemClick: ((ContinueWatchingItem) -> Unit)?,
    onItemLongPress: ((ContinueWatchingItem) -> Unit)?,
) {
    var minutes by rememberSaveable { mutableStateOf<Int?>(null) }
    val plan = remember(items, minutes) {
        minutes?.let { budget ->
            planTonight(items, budget * 60_000L) { SmartDownloadSettings.isGoal(it.parentMetaType, it.parentMetaId) }
        }.orEmpty()
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = sectionPadding)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Up next tonight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TONIGHT_CHOICES_MINUTES.forEach { choice ->
                FilterChip(
                    selected = minutes == choice,
                    onClick = { minutes = choice.takeIf { minutes != it } },
                    label = { Text(tonightLabel(choice)) },
                )
            }
        }
        val budget = minutes ?: return@Column
        if (plan.isEmpty()) {
            Text(
                "Nothing in Continue Watching fits in ${tonightLabel(budget)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = sectionPadding, vertical = 4.dp),
            )
            return@Column
        }
        HomeContinueWatchingSection(
            items = plan,
            style = style,
            dataSourceKey = dataSourceKey,
            title = "${plan.size} for tonight · ${formatWatchHours(plan.sumOf(::remainingMs))}",
            sectionPadding = sectionPadding,
            layout = layout,
            onItemClick = onItemClick,
            onItemLongPress = onItemLongPress,
        )
    }
}
