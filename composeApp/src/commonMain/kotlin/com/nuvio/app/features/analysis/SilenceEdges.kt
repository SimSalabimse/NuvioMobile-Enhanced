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
 * Moves intro and credits edges onto a boundary within [SEGMENT_AGREE_MS].
 * A black frame wins, then a quiet run, then an audio scene change.
 * A start moves to the end of a black or quiet run. An end moves to the start of it.
 * A scene change is a single cut, used for either edge.
 */
internal fun MediaAnalysisResult.snappingEdges(detected: DetectedSegments): DetectedSegments {
    val scenes = sceneChangeTimes()
    val intro = snapPair(detected.introStartMs, detected.introEndMs, "intro", scenes)
    val credits = snapPair(detected.creditsStartMs, detected.creditsEndMs, "outro", scenes)
    return detected.copy(
        introStartMs = intro?.first ?: detected.introStartMs,
        introEndMs = intro?.second ?: detected.introEndMs,
        creditsStartMs = credits?.first ?: detected.creditsStartMs,
        creditsEndMs = credits?.second ?: detected.creditsEndMs,
    )
}

/**
 * Quiet-run starts, black-run starts, and fingerprint cuts strictly after [timeMs], through [untilMs].
 * The recap chain uses the first of these as the end of a stitched opening.
 */
internal fun MediaAnalysisResult.edgesAfter(timeMs: Long, untilMs: Long): List<Long> {
    if (untilMs <= timeMs) return emptyList()
    val candidates = ArrayList<Long>()
    for (range in silentRanges) if (range.startMs > timeMs) candidates += range.startMs
    for (range in blackRanges) if (range.startMs > timeMs) candidates += range.startMs
    for (cut in sceneChangeTimes()) if (cut > timeMs) candidates += cut
    return candidates.filter { it <= untilMs }.sorted()
}

/** The first edge in (timeMs, timeMs + withinMs]. */
internal fun MediaAnalysisResult.nextEdgeAfter(timeMs: Long, withinMs: Long): Long? {
    if (withinMs <= 0L) return null
    return edgesAfter(timeMs, timeMs + withinMs).firstOrNull()
}

/**
 * Fingerprint cuts strictly after [timeMs], through [untilMs].
 * A season mark follows the cut where the recap ends, not a pause inside the matched audio.
 */
internal fun MediaAnalysisResult.sceneChangesAfter(timeMs: Long, untilMs: Long): List<Long> {
    if (untilMs <= timeMs) return emptyList()
    return sceneChangeTimes().filter { it > timeMs && it <= untilMs }
}

/** Snaps one detected range. Does not apply the rejection list; the caller does. */
internal fun MediaAnalysisResult.snapSegmentEdges(startMs: Long, endMs: Long): Pair<Long, Long>? {
    val scenes = sceneChangeTimes()
    val start = snapEdge(startMs, atStart = true, scenes)
    val end = snapEdge(endMs, atStart = false, scenes)
    if (end <= start) return null
    return start to end
}

internal fun MediaAnalysisResult.applyingSeasonDetection(
    others: List<MediaAnalysisResult>,
    theIntroDbIntro: Pair<Long, Long>? = null,
    theIntroDbCredits: Pair<Long, Long>? = null,
    trace: SegmentTrace? = null,
): MediaAnalysisResult {
    val prepared = rememberingSilentRanges()
    val detected = SeasonSegmentDetector.detect(
        prepared,
        others,
        theIntroDbIntro = theIntroDbIntro,
        theIntroDbCredits = theIntroDbCredits,
        trace = trace,
    )
    return prepared.applyingDetection(detected)
}

/**
 * Cuts in the audio fingerprint: a run of similar frames, then a jump.
 * Random frames stay near 16 differing bits and do not count, so this stays quiet
 * on noise and does not move an edge the silence snap already owns.
 */
internal fun sceneChanges(frames: IntArray, stepMs: Long, offsetMs: Long): List<Long> {
    if (frames.size < 8 || stepMs <= 0L) return emptyList()
    val diffs = IntArray(frames.size - 1) { index -> (frames[index] xor frames[index + 1]).countOneBits() }
    val out = ArrayList<Long>()
    var last = -10_000L
    for (index in 5 until diffs.size) {
        var stable = 0
        for (earlier in index - 5 until index) if (diffs[earlier] <= 8) stable++
        if (stable >= 4 && diffs[index] >= 20) {
            val at = offsetMs + (index + 1) * stepMs
            if (at - last >= 1_000L) {
                out += at
                last = at
            }
        }
    }
    return out
}

private fun MediaAnalysisResult.snapPair(
    startMs: Long?,
    endMs: Long?,
    type: String,
    scenes: List<Long>,
): Pair<Long, Long>? {
    if (startMs == null || endMs == null) return null
    val start = snapEdge(startMs, atStart = true, scenes)
    val end = snapEdge(endMs, atStart = false, scenes)
    if (end <= start) return null
    if (rejectedSegments.covers(type, start, end)) return null
    return start to end
}

private fun MediaAnalysisResult.sceneChangeTimes(): List<Long> {
    val step = fingerprintStepMs.coerceAtLeast(1).toLong()
    return sceneChanges(decodeFingerprint(headFingerprint), step, 0L) +
        sceneChanges(decodeFingerprint(tailFingerprint), step, tailStartMs)
}

private fun MediaAnalysisResult.snapEdge(timeMs: Long, atStart: Boolean, scenes: List<Long>): Long {
    nearestRunBoundary(blackRanges, timeMs, atStart)?.let { return it }
    nearestRunBoundary(silentRanges, timeMs, atStart)?.let { return it }
    nearestInstant(scenes, timeMs)?.let { return it }
    return timeMs
}

private fun nearestRunBoundary(ranges: List<SilentRange>, timeMs: Long, atStart: Boolean): Long? {
    var best: Long? = null
    var bestDistance = SEGMENT_AGREE_MS + 1
    for (range in ranges) {
        val boundary = if (atStart) range.endMs else range.startMs
        val distance = abs(boundary - timeMs)
        if (distance <= SEGMENT_AGREE_MS && distance < bestDistance) {
            best = boundary
            bestDistance = distance
        }
    }
    return best
}

private fun nearestInstant(times: List<Long>, timeMs: Long): Long? {
    var best: Long? = null
    var bestDistance = SEGMENT_AGREE_MS + 1
    for (time in times) {
        val distance = abs(time - timeMs)
        if (distance <= SEGMENT_AGREE_MS && distance < bestDistance) {
            best = time
            bestDistance = distance
        }
    }
    return best
}
