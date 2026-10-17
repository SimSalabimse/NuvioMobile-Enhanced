package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A season sheet probes at most this many missing sizes at once. */
internal const val SEASON_SIZE_PROBE_CONCURRENCY = 3

internal data class SeasonSourceChoice(
    val addonId: String,
    val qualityLabel: String,
    val bingeGroup: String?,
)

internal enum class SeasonMatchFailure {
    NoStreams,
    NoMatchingSource,
    UnsupportedFormat,
}

internal sealed class SeasonStreamMatch {
    data class Matched(val stream: StreamItem) : SeasonStreamMatch()
    data class Unmatched(val reason: SeasonMatchFailure) : SeasonStreamMatch()
}

internal data class SeasonSizeSummary(
    val selectedCount: Int,
    val knownBytes: Long,
    val unknownCount: Int,
)

internal data class SeasonProbeTarget(
    val id: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

private val seasonQualityLabelRegex = Regex("""(?i)\b(\d{3,4}p|4k|8k)\b""")

internal fun streamQualityLabel(stream: StreamItem): String {
    val texts = listOfNotNull(stream.name, stream.title, stream.description)
    for (text in texts) {
        val found = seasonQualityLabelRegex.find(text)?.value
        if (found != null) return found.lowercase()
    }
    return stream.streamLabel.trim().ifBlank { "Stream" }
}

internal fun StreamItem.isSeasonDownloadCandidate(): Boolean {
    val url = playableDirectUrl
    if (!url.isNullOrBlank()) return url.isSupportedDownloadUrl()
    return isDirectDebridStream || needsLocalDebridResolve
}

internal fun SeasonSourceChoice(stream: StreamItem): SeasonSourceChoice =
    SeasonSourceChoice(
        addonId = stream.addonId,
        qualityLabel = streamQualityLabel(stream),
        bingeGroup = stream.behaviorHints.bingeGroup?.trim()?.takeIf { it.isNotEmpty() },
    )

/**
 * Match another episode to the one source the user picked.
 * bingeGroup wins when the picked stream has one. Otherwise the same addon and quality label.
 * An unsupported hit stays unmatched instead of falling through to a different release.
 */
internal fun matchSeasonStream(
    choice: SeasonSourceChoice,
    streams: List<StreamItem>,
): SeasonStreamMatch {
    if (streams.isEmpty()) return SeasonStreamMatch.Unmatched(SeasonMatchFailure.NoStreams)

    val binge = choice.bingeGroup
    if (binge != null) {
        val bingeHits = streams.filter { it.behaviorHints.bingeGroup?.trim() == binge }
        if (bingeHits.isNotEmpty()) {
            return pickCandidate(bingeHits, choice)
        }
    }

    val labelHits = streams.filter { stream ->
        stream.addonId == choice.addonId && streamQualityLabel(stream) == choice.qualityLabel
    }
    if (labelHits.isNotEmpty()) return pickCandidate(labelHits, choice)
    return SeasonStreamMatch.Unmatched(SeasonMatchFailure.NoMatchingSource)
}

private fun pickCandidate(
    hits: List<StreamItem>,
    choice: SeasonSourceChoice,
): SeasonStreamMatch {
    val preferred = hits.firstOrNull { stream ->
        stream.isSeasonDownloadCandidate() &&
            stream.addonId == choice.addonId &&
            streamQualityLabel(stream) == choice.qualityLabel
    }
    val candidate = preferred ?: hits.firstOrNull { it.isSeasonDownloadCandidate() }
    return if (candidate != null) {
        SeasonStreamMatch.Matched(candidate)
    } else {
        SeasonStreamMatch.Unmatched(SeasonMatchFailure.UnsupportedFormat)
    }
}

internal fun summarizeSeasonSizes(knownBytes: List<Long?>): SeasonSizeSummary {
    var known = 0L
    var unknown = 0
    for (bytes in knownBytes) {
        if (bytes != null && bytes > 0L) known += bytes else unknown += 1
    }
    return SeasonSizeSummary(
        selectedCount = knownBytes.size,
        knownBytes = known,
        unknownCount = unknown,
    )
}

internal fun seasonSelectionTotalText(
    summary: SeasonSizeSummary,
    formatBytes: (Long) -> String,
): String {
    val count = "${summary.selectedCount} selected"
    if (summary.selectedCount == 0) return count
    if (summary.knownBytes <= 0L) {
        return if (summary.unknownCount > 0) "$count · Size unknown" else count
    }
    val formatted = formatBytes(summary.knownBytes)
    return if (summary.unknownCount > 0) {
        "$count · at least $formatted"
    } else {
        "$count · $formatted"
    }
}

/** Sizes in decimal units like iOS Settings (ByteCountFormatter .file on iOS). */
internal fun formatSeasonBytes(bytes: Long): String = formatBytesDecimal(bytes)

private fun oneDecimal(value: Double): Double = (value * 10.0).toLong() / 10.0

/**
 * Read a file size from a HEAD response or a one-byte ranged GET.
 * A 206 Content-Length is the range, not the file, unless Content-Range carries the total.
 */
internal fun remoteContentLengthBytes(
    statusCode: Int,
    headers: Map<String, List<String>>,
    requestWasRanged: Boolean,
): Long? {
    fun first(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    val rangeTotal = first("Content-Range")
        ?.substringAfter('/', missingDelimiterValue = "")
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
    if (rangeTotal != null) return rangeTotal

    val length = first("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    if (requestWasRanged && statusCode == 206) return null
    return length
}

internal suspend fun probeSeasonSizes(
    targets: List<SeasonProbeTarget>,
    maxInFlight: Int = SEASON_SIZE_PROBE_CONCURRENCY,
    probe: suspend (SeasonProbeTarget) -> Long?,
    onResult: (String, Long?) -> Unit,
) {
    if (targets.isEmpty()) return
    val limit = maxInFlight.coerceAtLeast(1)
    coroutineScope {
        val gate = Semaphore(limit)
        targets.forEach { target ->
            launch {
                val size = gate.withPermit {
                    runCatching { probe(target) }.getOrNull()
                }
                onResult(target.id, size?.takeIf { it > 0L })
            }
        }
    }
}

/** What the Downloads list already holds for one episode of the sheet. */
internal enum class SeasonExistingDownload {
    Downloaded,
    InProgress,
}

/**
 * [DownloadsRepository.enqueueFromStream] replaces an existing row for the same episode, which deletes
 * the finished file or the partial file. The season sheet must not do that to an episode the user
 * already has, so a finished, running, queued or paused download is left out. A failed one can be queued again.
 */
internal fun seasonExistingDownload(status: DownloadStatus?): SeasonExistingDownload? = when (status) {
    DownloadStatus.Completed -> SeasonExistingDownload.Downloaded
    DownloadStatus.Downloading,
    DownloadStatus.Queued,
    DownloadStatus.Paused,
    -> SeasonExistingDownload.InProgress
    DownloadStatus.Failed,
    null,
    -> null
}

/** The auto-play stream settings the player uses, read once for the sheet. */
internal data class SeasonAutoPickSettings(
    val mode: com.nuvio.app.features.streams.StreamAutoPlayMode,
    val regexPattern: String,
    val source: com.nuvio.app.features.streams.StreamAutoPlaySource,
    val selectedAddons: Set<String>,
    val selectedPlugins: Set<String>,
    val preferredBingeGroup: String?,
    val timeoutSeconds: Int,
    val debridEnabled: Boolean,
    val activeResolverProviderId: String?,
)

/** True when the player's auto-play would pick a stream on its own for these settings. */
internal fun SeasonAutoPickSettings.picksAutomatically(): Boolean =
    preferredBingeGroup != null ||
        when (mode) {
            com.nuvio.app.features.streams.StreamAutoPlayMode.MANUAL -> false
            com.nuvio.app.features.streams.StreamAutoPlayMode.FIRST_STREAM -> true
            com.nuvio.app.features.streams.StreamAutoPlayMode.REGEX_MATCH ->
                com.nuvio.app.features.streams.StreamAutoPlayPolicy.isRegexSelectionConfigured(regexPattern)
        }

/** What the auto-pick found on the streams answered so far. */
internal data class SeasonAutoPickResult(
    /** The stream to queue, or null. */
    val stream: StreamItem?,
    /** A matching TorBox/debrid torrent is still being cache-checked; the player would wait for it. */
    val waitingForDebrid: Boolean,
)

/**
 * The stream the player's auto-play would choose: the same [StreamAutoPlaySelector.evaluateAutoPlayStream]
 * call on the same, cache-annotated streams in the same order, with the same addon/plugin/regex/binge-group
 * and TorBox-cached rules. Only after that is the pick limited to streams the sheet can download: when the
 * player's choice can't be downloaded, the next ready stream in the player's order that can is used.
 */
internal fun evaluateSeasonAutoPick(
    streams: List<StreamItem>,
    settings: SeasonAutoPickSettings,
    installedAddonNames: Set<String>,
): SeasonAutoPickResult {
    if (!settings.picksAutomatically() || streams.isEmpty()) return SeasonAutoPickResult(null, false)
    val bingeGroup = settings.preferredBingeGroup
    val evaluation = com.nuvio.app.features.streams.StreamAutoPlaySelector.evaluateAutoPlayStream(
        streams = streams,
        mode = if (settings.mode == com.nuvio.app.features.streams.StreamAutoPlayMode.MANUAL) {
            com.nuvio.app.features.streams.StreamAutoPlayMode.FIRST_STREAM
        } else {
            settings.mode
        },
        regexPattern = settings.regexPattern,
        source = settings.source,
        installedAddonNames = installedAddonNames,
        selectedAddons = settings.selectedAddons,
        selectedPlugins = settings.selectedPlugins,
        preferredBingeGroup = bingeGroup,
        preferBingeGroupInSelection = bingeGroup != null,
        bingeGroupOnly = settings.mode == com.nuvio.app.features.streams.StreamAutoPlayMode.MANUAL,
        debridEnabled = settings.debridEnabled,
        activeResolverProviderId = settings.activeResolverProviderId,
    )
    val ready = buildList {
        evaluation.stream?.let(::add)
        evaluation.readyStreams.filterNot { it == evaluation.stream }.forEach(::add)
    }
    val picked = ready.firstOrNull { it.isSeasonDownloadCandidate() }
    return SeasonAutoPickResult(picked, waitingForDebrid = picked == null && evaluation.hasPendingDebridCandidate)
}

internal fun autoPickSeasonStream(
    streams: List<StreamItem>,
    settings: SeasonAutoPickSettings,
    installedAddonNames: Set<String>,
): StreamItem? = evaluateSeasonAutoPick(streams, settings, installedAddonNames).stream

/** Longest the sheet waits for a pick before showing the list, whatever the auto-play timeout is. */
internal const val SEASON_AUTO_PICK_GIVE_UP_MS = 60_000L

/**
 * Whether the sheet should stop waiting and show the full source list.
 * Only when nothing matched after every source answered and no TorBox/debrid cache check is
 * still running, or after [SEASON_AUTO_PICK_GIVE_UP_MS].
 */
internal fun seasonAutoPickShouldGiveUp(
    result: SeasonAutoPickResult,
    allSourcesAnswered: Boolean,
    hardTimeoutElapsed: Boolean,
): Boolean {
    if (result.stream != null) return false
    if (hardTimeoutElapsed) return true
    return allSourcesAnswered && !result.waitingForDebrid
}

/**
 * When the auto-pick may decide on the sources answered so far. Mirrors the stream screen:
 * 0 s decides on the first answer that matches, unlimited waits for every source,
 * otherwise the timeout or the last source, whichever comes first.
 */
internal fun seasonAutoPickMayDecide(
    timeoutSeconds: Int,
    timeoutElapsed: Boolean,
    allSourcesAnswered: Boolean,
): Boolean {
    if (allSourcesAnswered) return true
    if (timeoutSeconds == Int.MAX_VALUE) return false
    if (timeoutSeconds <= 0) return true
    return timeoutElapsed
}

/**
 * Season quality selector: match an episode at [targetBucket] (4K / 1080p / 720p / 480p), or the
 * nearest lower quality when that episode lacks it. Inside one quality, the picked stream's
 * bingeGroup wins, then its addon, then the first downloadable stream.
 */
internal fun matchSeasonStreamAtQuality(
    choice: SeasonSourceChoice,
    streams: List<StreamItem>,
    targetBucket: Int,
): SeasonStreamMatch {
    if (streams.isEmpty()) return SeasonStreamMatch.Unmatched(SeasonMatchFailure.NoStreams)
    val candidates = streams.filter { it.isSeasonDownloadCandidate() }
    if (candidates.isEmpty()) return SeasonStreamMatch.Unmatched(SeasonMatchFailure.UnsupportedFormat)
    for (bucket in DOWNLOAD_QUALITY_BUCKETS.filter { it <= targetBucket }) {
        val hits = candidates.filter { downloadQualityBucket(it) == bucket }
        if (hits.isEmpty()) continue
        val binge = choice.bingeGroup
        val pick = binge?.let { group -> hits.firstOrNull { it.behaviorHints.bingeGroup?.trim() == group } }
            ?: hits.firstOrNull { it.addonId == choice.addonId }
            ?: hits.first()
        return SeasonStreamMatch.Matched(pick)
    }
    return SeasonStreamMatch.Unmatched(SeasonMatchFailure.NoMatchingSource)
}
