package com.nuvio.app.features.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One pair of times for the same spoken moment.
 * [videoMs] is the picture. [subtitleMs] is the cue timestamp in the file.
 */
internal data class SubtitleAnchor(
    val videoMs: Long,
    val subtitleMs: Long,
)

/**
 * Values to set on mpv.
 * `sub-speed` multiplies cue timestamps. `sub-delay` is seconds added after that.
 * A cue that is late has a file time after the picture, so speed is below 1 or delay is negative.
 */
internal data class MpvSubtitleProperties(
    val subSpeed: Double,
    val subDelayMs: Long,
    val confident: Boolean,
)

internal fun fitSubtitleAnchors(anchors: List<SubtitleAnchor>): MpvSubtitleProperties {
    val usable = anchors.filter { it.videoMs >= 0L && it.subtitleMs >= 0L }.sortedBy { it.videoMs }
    if (usable.size < 2) return MpvSubtitleProperties(1.0, 0L, confident = false)
    val span = usable.last().videoMs - usable.first().videoMs
    if (span < 60_000L) return offsetOnly(usable)
    val (scale, intercept) = linearFit(usable) ?: return MpvSubtitleProperties(1.0, 0L, confident = false)
    if (scale <= 0.5 || scale >= 1.5) return MpvSubtitleProperties(1.0, 0L, confident = false)
    val residual = usable.maxOf { anchor ->
        abs(anchor.subtitleMs - (scale * anchor.videoMs + intercept))
    }
    if (residual > 1_500.0 || oneAnchorBreaksTheLine(usable, scale, intercept)) {
        return MpvSubtitleProperties(1.0, 0L, confident = false)
    }
    val speed = 1.0 / scale
    val delay = (-intercept / scale).roundToLong()
    return MpvSubtitleProperties(speed, delay, confident = true)
}

/** User delay is added to the fitted delay once. Callers keep the two numbers apart. */
internal fun withUserDelay(fit: MpvSubtitleProperties, userDelayMs: Long): MpvSubtitleProperties =
    fit.copy(subDelayMs = fit.subDelayMs + userDelayMs)

internal fun resetSubtitleTrack(): MpvSubtitleProperties =
    MpvSubtitleProperties(subSpeed = 1.0, subDelayMs = 0L, confident = true)

/** Property names and values the player sets. Speed is a factor. Delay is seconds. */
internal fun mpvPropertyValues(properties: MpvSubtitleProperties): Map<String, Double> {
    val speed = if (properties.subSpeed.isFinite() && properties.subSpeed > 0.5 && properties.subSpeed < 1.5) {
        properties.subSpeed
    } else {
        1.0
    }
    return mapOf(
        "sub-speed" to speed,
        "sub-delay" to properties.subDelayMs / 1_000.0,
    )
}

/** Where a cue is shown after mpv multiplies the file time by speed and adds delay. */
internal fun shownSubtitleMs(fileMs: Long, properties: MpvSubtitleProperties): Double =
    fileMs * properties.subSpeed + properties.subDelayMs

/**
 * A least-squares line can hide one bad anchor by tilting. Refit without the worst anchor.
 * If the rest agree and the left-out anchor is far from that line, this is not a fit.
 */
private fun oneAnchorBreaksTheLine(
    anchors: List<SubtitleAnchor>,
    scale: Double,
    intercept: Double,
): Boolean {
    if (anchors.size < 3) return false
    val worst = anchors.maxBy { anchorResidual(scale, intercept, it) }
    val rest = anchors.filter { it != worst }
    val reduced = linearFit(rest) ?: return true
    val restResidual = rest.maxOf { anchorResidual(reduced.first, reduced.second, it) }
    val excluded = anchorResidual(reduced.first, reduced.second, worst)
    return restResidual <= 1_500.0 && excluded > 1_500.0
}

private fun anchorResidual(scale: Double, intercept: Double, anchor: SubtitleAnchor): Double =
    abs(anchor.subtitleMs - (scale * anchor.videoMs + intercept))

private fun offsetOnly(anchors: List<SubtitleAnchor>): MpvSubtitleProperties {
    val offsets = anchors.map { it.subtitleMs - it.videoMs }.sorted()
    val median = offsets[offsets.size / 2]
    val spread = offsets.max() - offsets.min()
    if (spread > 400L) return MpvSubtitleProperties(1.0, 0L, confident = false)
    return MpvSubtitleProperties(subSpeed = 1.0, subDelayMs = -median, confident = true)
}

private fun linearFit(anchors: List<SubtitleAnchor>): Pair<Double, Double>? {
    val n = anchors.size.toDouble()
    var sumX = 0.0
    var sumY = 0.0
    var sumXX = 0.0
    var sumXY = 0.0
    anchors.forEach { anchor ->
        val x = anchor.videoMs.toDouble()
        val y = anchor.subtitleMs.toDouble()
        sumX += x
        sumY += y
        sumXX += x * x
        sumXY += x * y
    }
    val denom = n * sumXX - sumX * sumX
    if (abs(denom) < 1.0) return null
    val scale = (n * sumXY - sumX * sumY) / denom
    val intercept = (sumY - scale * sumX) / n
    return scale to intercept
}

private val providerAllowlist = setOf(
    "opensubtitles",
    "opensubtitles.com",
    "podnapisi",
    "addic7ed",
    "subscene",
)

internal data class SubtitleSourceLabel(
    val provider: String,
    val release: String?,
)

/** A provider name is shown only when the metadata says it. Otherwise the label is Unknown. */
internal fun subtitleSourceLabel(provider: String?, release: String?): SubtitleSourceLabel {
    val rawProvider = provider?.trim().orEmpty()
    val normalized = rawProvider.lowercase().removePrefix("www.")
    val known = normalized.isNotEmpty() &&
        normalized in providerAllowlist &&
        !rawProvider.contains("://") &&
        !rawProvider.contains('/')
    val shownProvider = if (known) provider!!.trim() else "Unknown"
    val shownRelease = release?.trim()?.takeIf { value ->
        value.isNotEmpty() &&
            value.length <= 120 &&
            !value.contains("://") &&
            !value.contains('@') &&
            !value.contains('/') &&
            !value.contains('\\') &&
            !value.contains('?')
    }
    return SubtitleSourceLabel(shownProvider, shownRelease)
}

internal fun subtitleSyncExportText(
    source: SubtitleSourceLabel,
    properties: MpvSubtitleProperties,
    userDelayMs: Long,
    anchorCount: Int,
    rateSupported: Boolean = true,
): String = redactSubtitleSyncExport(
    buildString {
        appendLine("provider=${source.provider}")
        appendLine("release=${source.release ?: "-"}")
        appendLine("subSpeed=${properties.subSpeed}")
        appendLine("subDelayMs=${properties.subDelayMs}")
        appendLine("userDelayMs=$userDelayMs")
        appendLine("anchors=$anchorCount")
        appendLine("confident=${properties.confident}")
        appendLine("rateSupported=$rateSupported")
    },
)

/** Drops a whole address, including the host. The analysis log keeps a host; this export does not. */
internal fun redactSubtitleSyncExport(text: String): String =
    text.replace(Regex("""(?i)\b(?:https?|addon)://\S+"""), "")

/** Last redacted sync export. Empty until a fit is recorded. Nothing here is uploaded. */
internal object SubtitleSyncExportLog {
    private var body: String? = null

    fun record(
        provider: String?,
        release: String?,
        properties: MpvSubtitleProperties,
        userDelayMs: Long,
        anchorCount: Int,
        rateSupported: Boolean = true,
    ) {
        body = subtitleSyncExportText(
            subtitleSourceLabel(provider, release),
            properties,
            userDelayMs,
            anchorCount,
            rateSupported,
        )
    }

    fun clear() {
        body = null
    }

    fun exportText(): String = body ?: subtitleSyncExportText(
        SubtitleSourceLabel("Unknown", null),
        MpvSubtitleProperties(1.0, 0L, confident = false),
        userDelayMs = 0L,
        anchorCount = 0,
    )
}

/** Session messages and the subtitle sync export are cleared together. */
internal fun clearSessionMessages() {
    com.nuvio.app.core.logging.InAppLogger.clear()
    SubtitleSyncExportLog.clear()
}
