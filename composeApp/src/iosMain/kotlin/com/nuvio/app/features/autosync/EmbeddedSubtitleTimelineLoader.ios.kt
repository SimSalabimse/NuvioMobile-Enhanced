package com.nuvio.app.features.autosync

import kotlinx.coroutines.CoroutineScope

internal actual object EmbeddedSubtitleTimelineLoader {
    actual fun prefetch(
        scope: CoroutineScope,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
    ) = Unit

    actual suspend fun load(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
    ): IndexedEmbeddedTimeline? = null
}
