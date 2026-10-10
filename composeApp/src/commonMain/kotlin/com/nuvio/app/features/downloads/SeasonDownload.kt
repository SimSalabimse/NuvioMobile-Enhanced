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

internal fun formatSeasonBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${oneDecimal(value / gib)} GB"
        value >= mib -> "${oneDecimal(value / mib)} MB"
        value >= kib -> "${oneDecimal(value / kib)} KB"
        else -> "$bytes B"
    }
}

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
