package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem

/**
 * When a debrid source can't be used (not cached, stale link): the first of the next few
 * [candidates] (already in the caller's preferred order) that resolves to a playable link.
 * Shared by manual play and downloads.
 */
internal object DebridSourceFallback {
    const val MAX_TRIES = 3

    suspend fun nextWorking(
        candidates: List<StreamItem>,
        failed: StreamItem,
        season: Int?,
        episode: Int?,
    ): StreamItem? = candidates
        .asSequence()
        .filter { it != failed && it.debridCacheStatus?.state != StreamDebridCacheState.NOT_CACHED }
        .filter { it.playableDirectUrl != null || DirectDebridPlaybackResolver.shouldResolveToPlayableStream(it) }
        .take(MAX_TRIES)
        .toList()
        .firstNotNullOfOrNull { candidate ->
            (DirectDebridPlaybackResolver.resolveToPlayableStream(candidate, season, episode) as? DirectDebridPlayableResult.Success)
                ?.stream?.takeIf { it.playableDirectUrl != null }
        }
}

/** Short "which source" text for the switch note: addon and its stream label, first line only. */
internal fun StreamItem.fallbackLabel(): String =
    listOf(addonName, streamLabel.lineSequence().firstOrNull().orEmpty())
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
        .take(60)
