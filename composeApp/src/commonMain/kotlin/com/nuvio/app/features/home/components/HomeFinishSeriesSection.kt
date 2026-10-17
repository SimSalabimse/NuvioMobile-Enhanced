package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.time.isEpisodeReleaseAired
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.parseRuntimeMinutes
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.delay

/*
 * Enhanced: "Finish a series" on Home. Shows that were started, ranked by the time left to finish
 * the whole series (every remaining aired episode, all seasons, minus what is already watched of
 * the current one), shortest first. Movies, finished, unaired and dropped shows are left out.
 * Cached metadata only; a missing season list is fetched quietly in the background.
 */

private const val TYPICAL_EPISODE_MINUTES = 45

internal data class FinishSeriesEntry(
    /** The Continue Watching / Next Up item of the show: tapping it plays the next unwatched episode. */
    val item: ContinueWatchingItem,
    val episodesLeft: Int,
    val minutesLeft: Long,
    /** Share of the series already watched, by time (0..1). */
    val seriesProgress: Float,
) {
    val label: String
        get() = "$episodesLeft ${if (episodesLeft == 1) "ep" else "eps"} · ${formatFinishMinutes(minutesLeft)} left"
}

internal fun formatFinishMinutes(minutes: Long): String {
    val m = minutes.coerceAtLeast(1L)
    val h = m / 60
    val rest = m % 60
    return when {
        h == 0L -> "$m min"
        rest == 0L -> "${h}h"
        else -> "${h}h ${rest}m"
    }
}

private fun MetaVideo.isAiredFor(nowEpochMs: Long): Boolean =
    available && (released?.let { isEpisodeReleaseAired(it, nowEpochMs) } ?: true)

private fun typicalRuntimeMinutes(meta: MetaDetails): Int {
    val runtimes = meta.videos.mapNotNull { it.runtime?.takeIf { minutes -> minutes > 0 } }.sorted()
    if (runtimes.isNotEmpty()) return runtimes[runtimes.size / 2]
    return parseRuntimeMinutes(meta.runtime)?.takeIf { it > 0 } ?: TYPICAL_EPISODE_MINUTES
}

/** Minutes already watched of the current (in-progress) episode. */
private fun ContinueWatchingItem.watchedMinutesOfCurrent(runtimeMinutes: Int): Long {
    if (isNextUp) return 0L
    val fraction = when {
        durationMs > 0L && resumePositionMs > 0L -> resumePositionMs.toFloat() / durationMs
        else -> progressFraction
    }.coerceIn(0f, 1f)
    return (runtimeMinutes * fraction).toLong()
}

internal fun buildFinishSeries(
    items: List<ContinueWatchingItem>,
    nowEpochMs: Long = WatchProgressClock.nowEpochMs(),
    metaLookup: (ContinueWatchingItem) -> MetaDetails?,
    isWatched: (ContinueWatchingItem, Int, Int) -> Boolean,
    isDropped: (String) -> Boolean,
    onMissingMeta: (ContinueWatchingItem) -> Unit = {},
): List<FinishSeriesEntry> {
    val seen = HashSet<String>()
    return items.asSequence()
        .filter { it.seasonNumber != null && it.episodeNumber != null && !it.isReleaseAlert }
        .filter { seen.add(it.parentMetaId) }
        .filterNot { isDropped(it.parentMetaId) }
        .mapNotNull { item ->
            val meta = metaLookup(item)
            if (meta == null) {
                onMissingMeta(item)
                return@mapNotNull null
            }
            val typical = typicalRuntimeMinutes(meta)
            val aired = meta.videos
                .filter { (it.season ?: 0) > 0 && it.episode != null && it.isAiredFor(nowEpochMs) }
                .distinctBy { it.season to it.episode }
                .sortedWith(compareBy<MetaVideo> { it.season }.thenBy { it.episode })
            if (aired.isEmpty()) return@mapNotNull null
            val currentSeason = item.seasonNumber!!
            val currentEpisode = item.episodeNumber!!
            fun isAtOrAfterCurrent(video: MetaVideo): Boolean {
                val season = video.season ?: 0
                return season > currentSeason || (season == currentSeason && (video.episode ?: 0) >= currentEpisode)
            }
            val remaining = aired.filter { video ->
                isAtOrAfterCurrent(video) &&
                    (video.season == currentSeason && video.episode == currentEpisode ||
                        !isWatched(item, video.season!!, video.episode!!))
            }
            if (remaining.isEmpty()) return@mapNotNull null
            val totalMinutes = aired.sumOf { (it.runtime?.takeIf { r -> r > 0 } ?: typical).toLong() }
            var minutesLeft = remaining.sumOf { (it.runtime?.takeIf { r -> r > 0 } ?: typical).toLong() }
            remaining.firstOrNull { it.season == currentSeason && it.episode == currentEpisode }?.let { current ->
                minutesLeft -= item.watchedMinutesOfCurrent(current.runtime?.takeIf { it > 0 } ?: typical)
            }
            minutesLeft = minutesLeft.coerceAtLeast(1L)
            val progress = if (totalMinutes > 0L) (1f - minutesLeft.toFloat() / totalMinutes).coerceIn(0f, 1f) else 0f
            FinishSeriesEntry(item, remaining.size, minutesLeft, progress)
        }
        .sortedBy { it.minutesLeft }
        .toList()
}

@Composable
internal fun HomeFinishSeriesSection(
    items: List<ContinueWatchingItem>,
    sectionPadding: Dp,
    modifier: Modifier = Modifier,
    onItemClick: ((ContinueWatchingItem) -> Unit)?,
    onItemLongPress: ((ContinueWatchingItem) -> Unit)? = null,
) {
    var metaVersion by remember { mutableStateOf(0) }
    val (entries, missing) = remember(items, metaVersion) {
        val pending = mutableSetOf<String>()
        val built = buildFinishSeries(
            items = items,
            metaLookup = { item -> runCatching { MetaDetailsRepository.peek(item.parentMetaType, item.parentMetaId) }.getOrNull() },
            isWatched = { item, season, episode ->
                runCatching {
                    WatchedRepository.isWatched(id = item.parentMetaId, type = item.parentMetaType, season = season, episode = episode)
                }.getOrDefault(false)
            },
            isDropped = { id -> runCatching { WatchProgressRepository.isDroppedShow(id) }.getOrDefault(false) },
            onMissingMeta = { item -> pending += "${item.parentMetaType}\u0000${item.parentMetaId}" },
        )
        built to pending.toSet()
    }
    // Season lists not cached yet: fetch them one by one in the background, then rebuild.
    LaunchedEffect(missing) {
        val todo = missing
        if (todo.isEmpty()) return@LaunchedEffect
        var fetched = 0
        for (key in todo) {
            val (type, id) = key.split('\u0000', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (id.isBlank()) continue
            val ok = runCatching { MetaDetailsRepository.fetch(type = type, id = id) }.getOrNull() != null
            if (ok) fetched++
            delay(250L)
        }
        if (fetched > 0) metaVersion++
    }
    if (entries.isEmpty()) return
    NuvioShelfSection(
        title = "Finish a series",
        entries = entries,
        modifier = modifier,
        headerHorizontalPadding = sectionPadding,
        rowContentPadding = PaddingValues(horizontal = sectionPadding),
        key = { entry -> "finish:" + entry.item.parentMetaId },
    ) { entry ->
        FinishSeriesCard(
            entry = entry,
            onClick = onItemClick?.let { click -> { click(entry.item) } },
            // Same hold menu as Continue Watching (Download, details, remove, ...).
            onLongClick = onItemLongPress?.let { press -> { press(entry.item) } },
        )
    }
}

@Composable
private fun FinishSeriesCard(
    entry: FinishSeriesEntry,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val item = entry.item
    val shape = RoundedCornerShape(12.dp)
    val image = item.background ?: item.imageUrl ?: item.poster
    Column(modifier = Modifier.width(220.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .posterCardClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    zoomImageUrl = image,
                    zoomCornerRadius = 12.dp,
                ),
        ) {
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { entry.seriesProgress },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        )
        // Right under the bar; wraps to a second line instead of cutting a word.
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            softWrap = true,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
