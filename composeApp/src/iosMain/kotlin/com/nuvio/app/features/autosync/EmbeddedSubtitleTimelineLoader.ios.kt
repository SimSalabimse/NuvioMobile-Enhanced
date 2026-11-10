package com.nuvio.app.features.autosync

import kotlinx.coroutines.CoroutineScope

/**
 * Not used by the iOS player. On 2e76ccee1 this type did not exist on iOS,
 * and the player still does not call it.
 */
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
