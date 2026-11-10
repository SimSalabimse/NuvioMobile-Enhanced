package com.nuvio.app.features.autosync

import kotlinx.coroutines.CoroutineScope

/**
 * Android loads embedded subtitle timing with the existing Media3 container parser.
 * iOS has no embedded index yet, so AutoSync keeps its live path there.
 */
internal expect object EmbeddedSubtitleTimelineLoader {
    fun prefetch(
        scope: CoroutineScope,
        sourceUrl: String,
        sourceHeaders: Map<String, String> = emptyMap(),
    )

    suspend fun load(
        sourceUrl: String,
        sourceHeaders: Map<String, String> = emptyMap(),
    ): IndexedEmbeddedTimeline?
}

internal data class IndexedEmbeddedTimeline(
    val tracks: List<ReferenceTrack>,
    val source: String,
    val bytesDownloaded: Long,
    val rangeRequests: Int,
    val loadMs: Long,
    val skipLiveFallbackWait: Boolean = false,
    val noSubtitleTracks: Boolean = false,
)
