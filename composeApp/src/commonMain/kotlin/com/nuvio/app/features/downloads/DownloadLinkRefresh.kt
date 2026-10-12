package com.nuvio.app.features.downloads

import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamItem

/**
 * Debrid and addon stream links expire, or are tied to the IP or session that asked for them.
 * A download resumed hours later (after a lock, a dropped connection or a network change) sends a
 * Range request to the same stored URL and the host answers 400/403/410 instead of 206. That used
 * to mark the row Failed with the raw "Request failed with HTTP 400" while the Range itself was
 * fine. These statuses now mean "get a fresh link for the same source and carry on".
 */
internal object DownloadLinkFailure {
    private const val PREFIX = "nuvio-link-rejected:"

    val refreshableStatuses: Set<Int> = setOf(400, 401, 403, 404, 410, 416)

    /** Refresh attempts per download before it is marked Failed. */
    const val MAX_REFRESHES: Int = 2

    fun isRefreshable(status: Int): Boolean = status in refreshableStatuses

    /** The platform writers pass this through `onFailure` instead of the raw HTTP message. */
    fun encode(status: Int): String = "$PREFIX$status"

    fun decode(message: String?): Int? =
        message?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toIntOrNull()

    private val legacyHttpMessage = Regex("""HTTP (\d{3})\b""")

    /** A row that failed before this build ("Request failed with HTTP 400") is retried with a fresh link. */
    fun legacyStatus(errorMessage: String?): Int? =
        errorMessage?.let { legacyHttpMessage.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.takeIf(::isRefreshable)
}

/**
 * The stream in [streams] that is the same source the row was downloading: same addon, then the
 * same bingeGroup, file name, stream title and description, or quality label, in that order.
 * Null when nothing matches well enough; a different release would not line up with the bytes on disk.
 */
internal fun pickRefreshedStream(item: DownloadItem, streams: List<StreamItem>): StreamItem? {
    val candidates = streams.filter { it.isSeasonDownloadCandidate() }
    if (candidates.isEmpty()) return null
    val addonId = item.providerAddonId?.takeIf { it.isNotBlank() }
    val sameAddon = if (addonId != null) candidates.filter { it.addonId == addonId } else candidates
    if (sameAddon.isEmpty()) return null

    item.sourceFilename?.takeIf { it.isNotBlank() }?.let { name ->
        sameAddon.firstOrNull { it.behaviorHints.filename?.trim() == name }?.let { return it }
    }
    sameAddon.firstOrNull {
        it.streamLabel == item.streamTitle && it.streamSubtitle.orEmpty() == item.streamSubtitle.orEmpty()
    }?.let { return it }
    item.sourceBingeGroup?.takeIf { it.isNotBlank() }?.let { binge ->
        sameAddon.firstOrNull { it.behaviorHints.bingeGroup?.trim() == binge }?.let { return it }
    }
    sameAddon.firstOrNull { it.streamLabel == item.streamTitle }?.let { return it }
    item.sourceQualityLabel?.takeIf { it.isNotBlank() }?.let { quality ->
        sameAddon.firstOrNull { streamQualityLabel(it) == quality }?.let { return it }
    }
    return null
}

internal data class RefreshedLink(
    val url: String,
    val headers: Map<String, String>,
    val responseHeaders: Map<String, String>,
)

/** Asks the row's addon (or debrid service) for the same item again. Null when that fails. */
internal suspend fun resolveFreshLink(item: DownloadItem): RefreshedLink? {
    val streams = SeasonStreamCatalog.loadFrom(
        addonId = item.providerAddonId.orEmpty(),
        type = item.contentType,
        videoId = item.videoId,
        season = item.seasonNumber,
        episode = item.episodeNumber,
    )
    val picked = pickRefreshedStream(item, streams) ?: return null
    val playable = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(picked)) {
        when (val result = DirectDebridPlaybackResolver.resolveToPlayableStream(picked, item.seasonNumber, item.episodeNumber)) {
            is DirectDebridPlayableResult.Success -> result.stream
            else -> return null
        }
    } else {
        picked
    }
    val url = playable.playableDirectUrl?.trim()?.takeIf { it.isNotBlank() && it.isSupportedDownloadUrl() } ?: return null
    return RefreshedLink(
        url = url,
        headers = playable.behaviorHints.proxyHeaders?.request.orEmpty(),
        responseHeaders = playable.behaviorHints.proxyHeaders?.response.orEmpty(),
    )
}

/** Keeps the app running for a refresh started while it is in the background. */
internal expect object DownloadBackgroundTime {
    fun begin(name: String): Any?
    fun end(token: Any?)
    fun isAppActive(): Boolean
}
