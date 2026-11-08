package com.nuvio.app.features.player

import kotlin.math.abs

/**
 * The audio/text matcher only searches about ±10 seconds. An offset sitting on that edge
 * is the search limit, not a measured lag, so it is not an anchor.
 */
internal const val SUBTITLE_SEARCH_WALL_MS = 9_500L

internal fun offsetSitsOnSearchWall(offsetMs: Long): Boolean = abs(offsetMs) >= SUBTITLE_SEARCH_WALL_MS

/**
 * Frame-rate pairs that actually show up between a video file and a subtitle file.
 * The list is fixed. A window tries 1.0 first and stops when that match is inside the search.
 */
internal fun candidateSubtitleScales(): List<Double> {
    val rates = doubleArrayOf(23.976, 24.0, 25.0, 29.97, 30.0)
    val scales = ArrayList<Double>()
    scales += 1.0
    for (fileRate in rates) {
        for (videoRate in rates) {
            if (fileRate == videoRate) continue
            val scale = fileRate / videoRate
            if (scale <= 0.5 || scale >= 1.5) continue
            if (scales.none { abs(it - scale) < 0.00001 }) scales += scale
        }
    }
    return scales
}

/**
 * Matches stored energy to cue text at [videoMs].
 * Scale 1.0 is the constant-offset path. Other scales are tried only when that path
 * cannot see the cues, and only so the existing ±10 second matcher can run on the residual.
 * The returned offset is the file-to-picture lag, which may be larger than 10 seconds.
 * A residual that lands on the search wall is refused.
 */
internal fun observeSubtitleScale(
    videoMs: Long,
    samples: List<AudioEnergySample>,
    cues: List<SubtitleSyncCue>,
    scales: List<Double> = candidateSubtitleScales(),
): SubtitleMatchObservation? {
    if (samples.isEmpty() || cues.isEmpty()) return null
    var bestScale = 0.0
    var bestResidual = Long.MAX_VALUE
    var found = false
    for (scale in scales) {
        val residual = usableResidual(videoMs, samples, cues, scale) ?: continue
        val closer = !found || abs(residual) + 150 < abs(bestResidual)
        val nearerIdentity = abs(abs(residual) - abs(bestResidual)) <= 150 && abs(scale - 1.0) < abs(bestScale - 1.0)
        if (closer || nearerIdentity) {
            bestScale = scale
            bestResidual = residual
            found = true
        }
        if (scale == 1.0 && found) break
    }
    if (!found) return SubtitleMatchObservation(videoMs, 0L, confident = false)
    val subtitleMs = (bestScale * (videoMs - bestResidual).toDouble()).toLong()
    if (subtitleMs < 0L) return SubtitleMatchObservation(videoMs, 0L, confident = false)
    return SubtitleMatchObservation(videoMs, videoMs - subtitleMs, confident = true)
}

/**
 * Picks one scale from the early windows, then uses only that scale later.
 * Two early windows must agree before a non-identity scale is kept.
 */
internal fun observeSubtitleWindows(
    centersMs: List<Long>,
    samplesAt: (Long) -> List<AudioEnergySample>,
    cues: List<SubtitleSyncCue>,
): List<SubtitleMatchObservation> {
    if (centersMs.isEmpty() || cues.isEmpty()) return emptyList()
    val scales = candidateSubtitleScales()
    val coarse = centersMs.take(2)
    val votes = mutableMapOf<Double, Int>()
    coarse.forEach { center ->
        val samples = samplesAt(center)
        val chosen = observeSubtitleScale(center, samples, cues, scales) ?: return@forEach
        if (!chosen.confident) return@forEach
        val scale = impliedScale(center, chosen.offsetMs) ?: return@forEach
        votes[scale] = (votes[scale] ?: 0) + 1
    }
    val identityVotes = votes[1.0] ?: 0
    val winner = votes.maxByOrNull { it.value }
    val scale = when {
        winner == null -> 1.0
        identityVotes > 0 && identityVotes >= winner.value -> 1.0
        winner.key != 1.0 && winner.value < 2 -> 1.0
        else -> winner.key
    }
    val limited = if (scale == 1.0) listOf(1.0) else listOf(scale)
    return centersMs.mapNotNull { center ->
        val samples = samplesAt(center)
        if (samples.isEmpty()) return@mapNotNull null
        observeSubtitleScale(center, samples, cues, limited)
    }
}

private fun impliedScale(videoMs: Long, offsetMs: Long): Double? {
    val subtitleMs = videoMs - offsetMs
    if (videoMs <= 0L || subtitleMs <= 0L) return 1.0
    val scale = subtitleMs.toDouble() / videoMs.toDouble()
    if (scale <= 0.5 || scale >= 1.5) return null
    return candidateSubtitleScales().minByOrNull { abs(it - scale) }?.takeIf { abs(it - scale) < 0.01 } ?: 1.0
}

private fun usableResidual(
    videoMs: Long,
    samples: List<AudioEnergySample>,
    cues: List<SubtitleSyncCue>,
    scale: Double,
): Long? {
    val warped = if (abs(scale - 1.0) < 0.0000001) {
        cues
    } else {
        cues.map { cue ->
            val start = (cue.startTimeMs / scale).toLong()
            val end = (cue.endTimeMs / scale).toLong().coerceAtLeast(start)
            SubtitleSyncCue(start, end, cue.text)
        }
    }
    val observed = matchObservation(videoMs, samples, warped) ?: return null
    if (!observed.confident || offsetSitsOnSearchWall(observed.offsetMs)) return null
    return observed.offsetMs
}
