package com.nuvio.app.features.home.components

import com.nuvio.app.core.time.isEpisodeReleaseAired
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.parseRuntimeMinutes
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressClock

/**
 * Enhanced: Continue Watching ordered by how easy each item is to finish, in sections
 * (Almost done, In progress, Next up, Stale). Only cached metadata is used (no fetching on Home).
 */
internal enum class ContinueWatchingEasySection(val title: String) {
    AlmostDone("Almost done"),
    InProgress("In progress"),
    NextUp("Next up"),
    Stale("Stale"),
}

internal data class ContinueWatchingTimeLeft(
    /** Total minutes to finish (this item, plus the rest of the season for shows), or null if unknown. */
    val minutesLeft: Long?,
    /** Episodes left including the current one; null for movies or without metadata. */
    val episodesLeft: Int?,
    val isLastEpisode: Boolean,
) {
    val label: String?
        get() {
            val minutes = minutesLeft ?: return null
            val time = formatMinutesLeft(minutes)
            val eps = episodesLeft
            return if (eps != null && eps > 1) "$eps eps left · $time" else "$time left"
        }
}

internal data class ContinueWatchingEasyEntry(
    val item: ContinueWatchingItem,
    val section: ContinueWatchingEasySection,
    val timeLeft: ContinueWatchingTimeLeft,
)

internal const val ALMOST_DONE_MINUTES = 15L
internal const val STALE_AFTER_MS = 30L * 24 * 60 * 60 * 1000
private const val TYPICAL_EPISODE_MINUTES = 45

internal fun formatMinutesLeft(minutes: Long): String {
    val m = minutes.coerceAtLeast(1L)
    val h = m / 60
    val rest = m % 60
    return when {
        h == 0L -> "$m min"
        rest == 0L -> "${h}h"
        else -> "${h}h ${rest}m"
    }
}

private fun ContinueWatchingItem.isShow(): Boolean = seasonNumber != null && episodeNumber != null

/** Minutes left in the item itself (the current episode or the movie). */
private fun ContinueWatchingItem.currentRemainingMinutes(fallbackRuntimeMinutes: Int?): Long? {
    if (isNextUp) return fallbackRuntimeMinutes?.toLong()
    if (durationMs > 0L) {
        val position = when {
            resumePositionMs > 0L -> resumePositionMs
            else -> (durationMs * progressFraction.coerceIn(0f, 1f)).toLong()
        }
        return ((durationMs - position).coerceAtLeast(0L) + 59_999L) / 60_000L
    }
    val runtime = fallbackRuntimeMinutes ?: return null
    return (runtime * (1f - progressFraction.coerceIn(0f, 1f))).toLong()
}

internal fun continueWatchingTimeLeft(
    item: ContinueWatchingItem,
    meta: MetaDetails?,
    nowEpochMs: Long,
): ContinueWatchingTimeLeft {
    val typical = meta?.let { typicalRuntimeMinutes(it) }
    if (!item.isShow()) {
        val movieRuntime = meta?.runtime?.let(::parseRuntimeMinutes)
        return ContinueWatchingTimeLeft(item.currentRemainingMinutes(movieRuntime), null, false)
    }
    val season = item.seasonNumber!!
    val episode = item.episodeNumber!!
    val current = meta?.videos?.firstOrNull { it.season == season && it.episode == episode }
    val currentMinutes = item.currentRemainingMinutes(current?.runtime ?: typical)
    if (meta == null) return ContinueWatchingTimeLeft(currentMinutes, null, false)
    val rest = meta.videos
        .asSequence()
        .filter { it.season == season && (it.episode ?: 0) > episode }
        .filter { it.isAiredFor(nowEpochMs) }
        .distinctBy { it.episode }
        .toList()
    val restMinutes = rest.sumOf { (it.runtime ?: typical ?: TYPICAL_EPISODE_MINUTES).toLong() }
    val total = currentMinutes?.let { it + restMinutes }
    return ContinueWatchingTimeLeft(
        minutesLeft = total,
        episodesLeft = rest.size + 1,
        isLastEpisode = rest.isEmpty(),
    )
}

private fun MetaVideo.isAiredFor(nowEpochMs: Long): Boolean =
    available && (released?.let { isEpisodeReleaseAired(it, nowEpochMs) } ?: true)

private fun typicalRuntimeMinutes(meta: MetaDetails): Int? {
    val runtimes = meta.videos.mapNotNull { it.runtime?.takeIf { minutes -> minutes > 0 } }.sorted()
    if (runtimes.isNotEmpty()) return runtimes[runtimes.size / 2]
    return parseRuntimeMinutes(meta.runtime)
}

/** Sections in order, each sorted by the least time left; unknown times go last in a section. */
internal fun buildEasiestToFinish(
    items: List<ContinueWatchingItem>,
    nowEpochMs: Long = WatchProgressClock.nowEpochMs(),
    metaLookup: (ContinueWatchingItem) -> MetaDetails? = { item ->
        runCatching { MetaDetailsRepository.peek(item.parentMetaType, item.parentMetaId) }.getOrNull()
    },
): List<ContinueWatchingEasyEntry> {
    val metaCache = HashMap<String, MetaDetails?>()
    return items.map { item ->
        val meta = metaCache.getOrPut("${item.parentMetaType}:${item.parentMetaId}") { metaLookup(item) }
        val timeLeft = continueWatchingTimeLeft(item, meta, nowEpochMs)
        val stale = item.lastActivityEpochMs > 0L && nowEpochMs - item.lastActivityEpochMs > STALE_AFTER_MS
        val section = when {
            stale -> ContinueWatchingEasySection.Stale
            item.isNextUp && timeLeft.isLastEpisode.not() -> ContinueWatchingEasySection.NextUp
            (timeLeft.minutesLeft != null && timeLeft.minutesLeft < ALMOST_DONE_MINUTES) ||
                (timeLeft.isLastEpisode && !item.isNextUp) -> ContinueWatchingEasySection.AlmostDone
            item.isNextUp -> ContinueWatchingEasySection.NextUp
            else -> ContinueWatchingEasySection.InProgress
        }
        ContinueWatchingEasyEntry(item, section, timeLeft)
    }.sortedWith(
        compareBy<ContinueWatchingEasyEntry> { it.section.ordinal }
            .thenBy { it.timeLeft.minutesLeft ?: Long.MAX_VALUE }
            .thenByDescending { it.item.lastActivityEpochMs },
    )
}
