package com.nuvio.app.features.stats

import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.watch_stats_title
import nuvio.composeapp.generated.resources.watch_stats_empty
import nuvio.composeapp.generated.resources.watch_stats_total
import nuvio.composeapp.generated.resources.watch_stats_today
import nuvio.composeapp.generated.resources.watch_stats_streak
import nuvio.composeapp.generated.resources.watch_stats_longest_streak
import nuvio.composeapp.generated.resources.watch_stats_top_titles
import nuvio.composeapp.generated.resources.watch_stats_recap_title
import nuvio.composeapp.generated.resources.watch_stats_recap_top

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
import com.nuvio.app.core.ui.nuvio
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import org.jetbrains.compose.resources.pluralStringResource
import nuvio.composeapp.generated.resources.watch_stats_days_count
import nuvio.composeapp.generated.resources.watch_stats_recap_hours_days
import nuvio.composeapp.generated.resources.watch_stats_recap_titles_finished
import nuvio.composeapp.generated.resources.watch_stats_recap_streak_days
import nuvio.composeapp.generated.resources.watch_stats_last_7_days
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
        val tokens = MaterialTheme.nuvio
        SettingsSection(title = stringResource(Res.string.watch_stats_title), isTablet = isTablet) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (summary.totalMs <= 0L) {
                    StatsCard {
                        Text(
                            text = stringResource(Res.string.watch_stats_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(stringResource(Res.string.watch_stats_total), formatWatchHours(summary.totalMs), Modifier.weight(1f))
                    StatTile(stringResource(Res.string.watch_stats_today), formatWatchHours(summary.todayMs), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(
                        stringResource(Res.string.watch_stats_streak),
                        pluralStringResource(Res.plurals.watch_stats_days_count, summary.currentStreakDays, summary.currentStreakDays),
                        Modifier.weight(1f),
                    )
                    StatTile(
                        stringResource(Res.string.watch_stats_longest_streak),
                        pluralStringResource(Res.plurals.watch_stats_days_count, summary.longestStreakDays, summary.longestStreakDays),
                        Modifier.weight(1f),
                    )
                }
                StatsCard {
                    Text(
                        stringResource(Res.string.watch_stats_last_7_days),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.colors.textPrimary,
                    )
                    Last7DaysChart(summary.last7Days)
                }
                if (summary.topShows.isNotEmpty()) {
                    StatsCard {
                        Text(
                            text = stringResource(Res.string.watch_stats_top_titles),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.colors.textPrimary,
                        )
                        summary.topShows.forEachIndexed { index, (_, show) ->
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${index + 1}.",
                                    modifier = Modifier.width(24.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.colors.textMuted,
                                )
                                Text(
                                    show.title,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.colors.textPrimary,
                                )
                                Text(formatWatchHours(show.watchedMs), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
                            }
                        }
                    }
                }
                WeeklyRecapCard(data)
                YearRecapCard(summary)
            }
        }
    }
}

@Composable
private fun StatsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        border = BorderStroke(1.dp, tokens.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier,
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        border = BorderStroke(1.dp, tokens.colors.borderSubtle),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tokens.colors.textPrimary)
        }
    }
}

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Weekday of an ISO date (1970-01-01 was a Thursday). */
private fun weekdayLabel(isoDay: String): String {
    val n = isoDayNumber(isoDay) ?: return isoDay.takeLast(2)
    return WEEKDAYS[((n % 7) + 7 + 3) % 7]
}

internal fun minutesLabel(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> "–"
        // Compact ("1h30") so it fits a seventh of a 320pt-wide screen.
        minutes >= 60 -> "${minutes / 60}h${(minutes % 60).takeIf { it > 0 }?.toString()?.padStart(2, '0') ?: ""}"
        else -> "${minutes.coerceAtLeast(1)}m"
    }
}

@Composable
private fun Last7DaysChart(days: List<Pair<String, Long>>) {
    val tokens = MaterialTheme.nuvio
    val max = (days.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val accent = tokens.colors.accent
    val barArea = 88.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { (day, ms) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(minutesLabel(ms), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textMuted, maxLines = 1)
                Box(modifier = Modifier.fillMaxWidth().height(barArea), contentAlignment = Alignment.BottomCenter) {
                    val fraction = if (ms > 0L) (ms.toFloat() / max.toFloat()).coerceIn(0.06f, 1f) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .height(if (ms > 0L) barArea * fraction else 3.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (ms > 0L) accent else tokens.colors.borderSubtle),
                    )
                }
                Text(weekdayLabel(day), style = MaterialTheme.typography.labelSmall, color = tokens.colors.textPrimary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun WeeklyRecapCard(data: WatchStatsData) {
    val fullyWatched by WatchedRepository.fullyWatchedSeriesKeys.collectAsStateWithLifecycle()
    val recap = remember(data, fullyWatched) {
        WatchStats.weeklyRecap(data, isSeriesFinished = { id, type -> watchedItemKeys(type, id).any(fullyWatched::contains) })
    }
    if (recap.watchedMs <= 0L && recap.skippedMs <= 0L) return
    val tokens = MaterialTheme.nuvio
    StatsCard {
        Text("This week", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tokens.colors.textPrimary)
        listOfNotNull(
            "${formatWatchHours(recap.watchedMs)} watched",
            recap.finishedSeries.takeIf { it.isNotEmpty() }?.let { "Finished: ${it.joinToString()}" },
            recap.skippedMs.takeIf { it > 0L }?.let { "${formatWatchHours(it)} saved by skipping intros, recaps and credits" },
        ).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
        }
    }
}

@Composable
private fun YearRecapCard(summary: WatchStatsSummary) {
    val tokens = MaterialTheme.nuvio
    val accent = tokens.colors.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f), tokens.colors.surface)))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(Res.string.watch_stats_recap_title, summary.year),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            pluralStringResource(Res.plurals.watch_stats_recap_hours_days, summary.daysWatchedThisYear, summary.daysWatchedThisYear, formatWatchHours(summary.yearMs)),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Text(
            pluralStringResource(Res.plurals.watch_stats_recap_titles_finished, summary.yearCompleted, summary.yearCompleted),
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
            pluralStringResource(Res.plurals.watch_stats_recap_streak_days, summary.longestStreakDays, summary.longestStreakDays),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}
