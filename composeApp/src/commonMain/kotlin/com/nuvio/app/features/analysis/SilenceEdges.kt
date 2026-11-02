package com.nuvio.app.features.analysis

import kotlin.math.abs

/** Level at or below -60 dBFS: byte 0 is -90 dBFS and byte 255 is 0 dBFS. */
private const val SILENCE_LEVEL = 85
private const val MIN_SILENCE_MS = 300L

/** Quiet runs of [energy], long enough to be an edge. Empty when there is no track. */
internal fun silentRangesFromEnergy(energy: String?, stepMs: Int): List<SilentRange> {
    val levels = decodeEnergyLevels(energy)
    if (levels.isEmpty()) return emptyList()
    val step = stepMs.coerceAtLeast(1)
    val minSamples = ((MIN_SILENCE_MS + step - 1) / step).toInt().coerceAtLeast(1)
    val ranges = mutableListOf<SilentRange>()
    var runStart = -1
    for (index in levels.indices) {
        if (levels[index] <= SILENCE_LEVEL) {
            if (runStart < 0) runStart = index
        } else if (runStart >= 0) {
            if (index - runStart >= minSamples) {
                ranges += SilentRange(runStart * step.toLong(), index * step.toLong())
            }
            runStart = -1
        }
    }
    if (runStart >= 0 && levels.size - runStart >= minSamples) {
        ranges += SilentRange(runStart * step.toLong(), levels.size * step.toLong())
    }
    return ranges
}

/** Replaces [MediaAnalysisResult.silentRanges] from the energy track while that track is still here. */
internal fun MediaAnalysisResult.rememberingSilentRanges(): MediaAnalysisResult {
    if (energy.isNullOrEmpty()) return this
    return copy(silentRanges = silentRangesFromEnergy(energy, energyStepMs))
}

/**
 * Moves intro and credits edges onto a quiet-run boundary within [SEGMENT_AGREE_MS].
 * A start moves to the end of the quiet run. An end moves to the start of it.
 */
internal fun MediaAnalysisResult.snappingEdges(detected: DetectedSegments): DetectedSegments {
    val intro = snapPair(detected.introStartMs, detected.introEndMs, "intro")
    val credits = snapPair(detected.creditsStartMs, detected.creditsEndMs, "outro")
    return detected.copy(
        introStartMs = intro?.first ?: detected.introStartMs,
        introEndMs = intro?.second ?: detected.introEndMs,
        creditsStartMs = credits?.first ?: detected.creditsStartMs,
        creditsEndMs = credits?.second ?: detected.creditsEndMs,
    )
}

internal fun MediaAnalysisResult.applyingSeasonDetection(others: List<MediaAnalysisResult>): MediaAnalysisResult {
    val prepared = rememberingSilentRanges()
    val detected = SeasonSegmentDetector.detect(prepared, others)
    return prepared.applyingDetection(prepared.snappingEdges(detected))
}

private fun MediaAnalysisResult.snapPair(startMs: Long?, endMs: Long?, type: String): Pair<Long, Long>? {
    if (startMs == null || endMs == null) return null
    val start = snapEdge(startMs, atStart = true)
    val end = snapEdge(endMs, atStart = false)
    if (end <= start) return null
    if (rejectedSegments.covers(type, start, end)) return null
    return start to end
}

private fun MediaAnalysisResult.snapEdge(timeMs: Long, atStart: Boolean): Long {
    var best = timeMs
    var bestDistance = SEGMENT_AGREE_MS + 1
    for (range in silentRanges) {
        val boundary = if (atStart) range.endMs else range.startMs
        val distance = abs(boundary - timeMs)
        if (distance <= SEGMENT_AGREE_MS && distance < bestDistance) {
            best = boundary
            bestDistance = distance
        }
    }
    return best
}
