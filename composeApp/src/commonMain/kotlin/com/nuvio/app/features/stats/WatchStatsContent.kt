package com.nuvio.app.features.stats

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_stats_title
import nuvio.composeapp.generated.resources.watch_stats_empty
import nuvio.composeapp.generated.resources.watch_stats_total
import nuvio.composeapp.generated.resources.watch_stats_today
import nuvio.composeapp.generated.resources.watch_stats_streak
import nuvio.composeapp.generated.resources.watch_stats_longest_streak
import nuvio.composeapp.generated.resources.watch_stats_days
import nuvio.composeapp.generated.resources.watch_stats_top_titles
import nuvio.composeapp.generated.resources.watch_stats_recap_title
import nuvio.composeapp.generated.resources.watch_stats_recap_hours
import nuvio.composeapp.generated.resources.watch_stats_recap_completed
import nuvio.composeapp.generated.resources.watch_stats_recap_top
import nuvio.composeapp.generated.resources.watch_stats_recap_streak

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.settings.SettingsSection
import org.jetbrains.compose.resources.stringResource

internal fun formatWatchHours(ms: Long): String {
    val minutes = ms / 60_000L
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

/** Local watch stats (Settings > Profile): hours, streaks, top titles, last 7 days, yearly recap. */
internal fun LazyListScope.watchStatsContent(isTablet: Boolean) {
    item(key = "local_watch_stats") {
        LaunchedEffect(Unit) { WatchStats.ensureLoaded() }
        val data by WatchStats.data.collectAsStateWithLifecycle()
        val summary = remember(data) { WatchStats.summarize(data) }
        SettingsSection(title = stringResource(Res.string.watch_stats_title), isTablet = isTablet) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (summary.totalMs <= 0L) {
                    Text(
                        text = stringResource(Res.string.watch_stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(stringResource(Res.string.watch_stats_total), formatWatchHours(summary.totalMs), Modifier.weight(1f))
                    StatTile(stringResource(Res.string.watch_stats_today), formatWatchHours(summary.todayMs), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(
                        stringResource(Res.string.watch_stats_streak),
                        stringResource(Res.string.watch_stats_days, summary.currentStreakDays),
                        Modifier.weight(1f),
                    )
                    StatTile(
                        stringResource(Res.string.watch_stats_longest_streak),
                        stringResource(Res.string.watch_stats_days, summary.longestStreakDays),
                        Modifier.weight(1f),
                    )
                }
                Last7DaysChart(summary.last7Days)
                if (summary.topShows.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.watch_stats_top_titles),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    summary.topShows.forEachIndexed { index, (_, show) ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}.", modifier = Modifier.width(24.dp), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                show.title,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(formatWatchHours(show.watchedMs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                YearRecapCard(summary)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Last7DaysChart(days: List<Pair<String, Long>>) {
    val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { (day, ms) ->
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                val fraction = (ms.toFloat() / max.toFloat()).coerceIn(0.03f, 1f)
                if (fraction < 1f) Spacer(Modifier.weight(1f - fraction))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(fraction)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (ms > 0L) accent else accent.copy(alpha = 0.2f)),
                )
                Text(day.takeLast(2), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun YearRecapCard(summary: WatchStatsSummary) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f), Color(0xFF1B1B2F))))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(Res.string.watch_stats_recap_title, summary.year),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            stringResource(Res.string.watch_stats_recap_hours, formatWatchHours(summary.yearMs), summary.daysWatchedThisYear),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Text(
            stringResource(Res.string.watch_stats_recap_completed, summary.yearCompleted),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        summary.yearTopShows.firstOrNull()?.let { (show, ms) ->
            Text(
                stringResource(Res.string.watch_stats_recap_top, show.title, formatWatchHours(ms)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        Text(
            stringResource(Res.string.watch_stats_recap_streak, summary.longestStreakDays),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
