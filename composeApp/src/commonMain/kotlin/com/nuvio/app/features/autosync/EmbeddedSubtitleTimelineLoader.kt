package com.nuvio.app.features.autosync

import kotlinx.coroutines.CoroutineScope

/**
 * Android loads embedded subtitle timing with the Media3 container parser.
 * The iOS player does not call this. Its actual returns null so the shared
 * declaration compiles, and iOS Auto Sync stays on the audio-energy path.
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
