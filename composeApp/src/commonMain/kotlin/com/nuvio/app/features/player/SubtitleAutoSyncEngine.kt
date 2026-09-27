package com.nuvio.app.features.player

import kotlin.math.abs
import kotlin.math.max

/**
 * Matches subtitle cues to the audio energy inside the capture.
 * The offset is how far the cues must move to sit on the louder bins.
 */
object SubtitleAutoSyncEngine {

    private const val SAMPLE_WINDOW_MS = 100L
    private const val MAX_ANALYSIS_DURATION_MS = 60_000L
    private const val MAX_OFFSET_SEARCH_MS = 10_000L
    private const val OFFSET_STEP_MS = 100L
    /** A second alignment has to sit this far from the winner to count. */
    private const val RIVAL_GAP_MS = 1_500
    /** Separation required before Subtitle Delay may move. */
    private const val MIN_SEPARATION = 0.3
    /** Quiet-bed percentile removed before cue energy is scored. */
    private const val SPEECH_FLOOR_PERCENTILE = 0.35
    /** Inside this band the result stays at 0 and Subtitle Delay is not written. */
    private const val ZERO_DEADZONE_MS = 300

    /**
     * @param audioSamples Audio amplitude samples with timestamps
     * @param subtitleCues Subtitle cues with timing information
     * @param currentPositionMs Playback position the analysis window is centered on
     */
    fun computeOptimalOffset(
        audioSamples: List<AudioEnergySample>,
        subtitleCues: List<SubtitleSyncCue>,
        currentPositionMs: Long,
    ): SubtitleAutoSyncResult? {
        if (audioSamples.isEmpty() || subtitleCues.isEmpty()) {
            return SubtitleAutoSyncResult.Error("Insufficient data for auto-sync")
        }

        val windowStart = max(0L, currentPositionMs - MAX_ANALYSIS_DURATION_MS / 2)
        val windowEnd = currentPositionMs + MAX_ANALYSIS_DURATION_MS / 2
        val windowedAudio = audioSamples.filter { it.timestampMs in windowStart..windowEnd }
        val windowedCues = subtitleCues.filter { it.startTimeMs in windowStart..windowEnd }

        if (windowedAudio.isEmpty() || windowedCues.isEmpty()) {
            return SubtitleAutoSyncResult.Error("No subtitle or audio data in analysis window")
        }
        if (!cuesOverlapAudio(windowedCues, windowedAudio)) {
            return SubtitleAutoSyncResult.Error(energyMatchSpanMessage(windowedAudio))
        }

        val envelope = buildLiftedEnvelope(windowedAudio, SAMPLE_WINDOW_MS)
        if (envelope.size < 2) {
            return SubtitleAutoSyncResult.Error(energyMatchSpanMessage(windowedAudio))
        }

        val decision = chooseOffset(scoreOffsets(envelope, windowedCues))
        return if (decision.confidence >= MIN_SEPARATION && decision.excess > 0.0) {
            SubtitleAutoSyncResult.Success(decision.offsetMs, decision.confidence)
        } else {
            SubtitleAutoSyncResult.LowConfidence(decision.offsetMs, decision.confidence)
        }
    }

    private fun cuesOverlapAudio(
        cues: List<SubtitleSyncCue>,
        audio: List<AudioEnergySample>,
    ): Boolean {
        val audioStart = audio.minOf { it.timestampMs }
        val audioEnd = audio.maxOf { it.timestampMs }
        return cues.any { cue ->
            val cueEnd = if (cue.endTimeMs > cue.startTimeMs) cue.endTimeMs else cue.startTimeMs + SAMPLE_WINDOW_MS
            cue.startTimeMs <= audioEnd && cueEnd >= audioStart
        }
    }

    private fun buildLiftedEnvelope(
        samples: List<AudioEnergySample>,
        windowMs: Long,
    ): List<EnergyPoint> {
        val raw = buildRawEnergyEnvelope(samples, windowMs)
        if (raw.isEmpty()) return raw
        val floor = percentile(raw.map { it.value }, SPEECH_FLOOR_PERCENTILE)
        return raw.map { it.copy(value = max(0.0, it.value - floor)) }
    }

    private fun buildRawEnergyEnvelope(
        samples: List<AudioEnergySample>,
        windowMs: Long,
    ): List<EnergyPoint> {
        if (samples.isEmpty()) return emptyList()

        val startTime = samples.first().timestampMs
        val endTime = samples.last().timestampMs
        val points = mutableListOf<EnergyPoint>()
        var windowStart = startTime
        while (windowStart <= endTime) {
            val windowEnd = windowStart + windowMs
            var sum = 0.0
            var count = 0
            for (sample in samples) {
                if (sample.timestampMs >= windowStart && sample.timestampMs < windowEnd) {
                    sum += sample.energy
                    count += 1
                }
            }
            if (count > 0) {
                points.add(EnergyPoint(windowStart + windowMs / 2, sum / count))
            }
            windowStart += windowMs
        }
        return points
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val ordered = values.sorted()
        val index = (fraction * (ordered.size - 1)).toInt().coerceIn(0, ordered.lastIndex)
        return ordered[index]
    }

    private fun scoreOffsets(
        envelope: List<EnergyPoint>,
        cues: List<SubtitleSyncCue>,
    ): List<OffsetScore> {
        val centers = LongArray(envelope.size) { envelope[it].timestampMs }
        val prefix = DoubleArray(envelope.size + 1)
        for (index in envelope.indices) {
            prefix[index + 1] = prefix[index] + envelope[index].value
        }
        val total = prefix[prefix.lastIndex]
        val scores = ArrayList<OffsetScore>(201)
        var offset = -MAX_OFFSET_SEARCH_MS
        while (offset <= MAX_OFFSET_SEARCH_MS) {
            var inside = 0.0
            var count = 0
            var covered = 0
            var expected = 0L
            for (cue in cues) {
                val start = cue.startTimeMs + offset
                var end = cue.endTimeMs + offset
                if (end <= start) end = start + SAMPLE_WINDOW_MS
                val from = lowerBound(centers, start)
                val to = lowerBound(centers, end)
                val bins = to - from
                inside += prefix[to] - prefix[from]
                count += bins
                covered += bins
                expected += max(1L, (end - start) / SAMPLE_WINDOW_MS)
            }
            val excess = if (count == 0 || expected <= 0L) {
                0.0
            } else {
                val outsideCount = envelope.size - count
                val meanIn = inside / count
                val meanOut = if (outsideCount > 0) (total - inside) / outsideCount else 0.0
                val coverage = covered.toDouble() / expected.toDouble()
                (meanIn - meanOut) * coverage * coverage
            }
            scores.add(OffsetScore(offset.toInt(), excess))
            offset += OFFSET_STEP_MS
        }
        return scores
    }

    private fun lowerBound(centers: LongArray, target: Long): Int {
        var lo = 0
        var hi = centers.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (centers[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun chooseOffset(scores: List<OffsetScore>): OffsetDecision {
        var best = scores.first()
        for (score in scores) {
            if (scoreBeats(score, best)) best = score
        }
        val rival = bestRival(scores, best.offsetMs)
        val zero = scores.first { it.offsetMs == 0 }
        val winnerMargin = separation(best.excess, rival.excess)
        if (best.offsetMs != 0 &&
            winnerMargin >= MIN_SEPARATION &&
            best.excess > zero.excess &&
            best.excess > 0.0
        ) {
            val offset = if (abs(best.offsetMs) <= ZERO_DEADZONE_MS) 0 else best.offsetMs
            return OffsetDecision(offset, winnerMargin, best.excess)
        }
        val zeroRival = bestRival(scores, 0)
        return OffsetDecision(0, separation(zero.excess, zeroRival.excess), zero.excess)
    }

    private fun bestRival(scores: List<OffsetScore>, anchorMs: Int): OffsetScore {
        var rival: OffsetScore? = null
        for (score in scores) {
            if (abs(score.offsetMs - anchorMs) < RIVAL_GAP_MS) continue
            if (rival == null || scoreBeats(score, rival)) rival = score
        }
        return rival ?: OffsetScore(anchorMs, 0.0)
    }

    private fun scoreBeats(candidate: OffsetScore, incumbent: OffsetScore): Boolean {
        if (candidate.excess != incumbent.excess) return candidate.excess > incumbent.excess
        val candidateAbs = abs(candidate.offsetMs)
        val incumbentAbs = abs(incumbent.offsetMs)
        if (candidateAbs != incumbentAbs) return candidateAbs < incumbentAbs
        return candidate.offsetMs < incumbent.offsetMs
    }

    private fun separation(winner: Double, other: Double): Double {
        val denom = abs(winner) + abs(other)
        if (denom == 0.0) return 0.0
        return (winner - other) / denom
    }

    private data class EnergyPoint(
        val timestampMs: Long,
        val value: Double,
    )

    private data class OffsetScore(
        val offsetMs: Int,
        val excess: Double,
    )

    private data class OffsetDecision(
        val offsetMs: Int,
        val confidence: Double,
        val excess: Double,
    )
}

internal const val ENERGY_MATCH_SPAN_LABEL = "Energy match span"

internal fun energyMatchSpanMessage(samples: List<AudioEnergySample>): String {
    val start = samples.minOfOrNull { it.timestampMs } ?: 0L
    val end = samples.maxOfOrNull { it.timestampMs } ?: 0L
    return "${formatEnergyMatchSpan(samples)} (${formatEnergyStats(samples)}, audio ${start}-${end}ms)"
}

internal fun formatEnergyMatchSpan(samples: List<AudioEnergySample>): String {
    if (samples.isEmpty()) return "$ENERGY_MATCH_SPAN_LABEL 0.0s"
    val durationMs = samples.maxOf { it.timestampMs } - samples.minOf { it.timestampMs }
    val tenths = durationMs / 100
    val whole = tenths / 10
    val fraction = tenths % 10
    return "$ENERGY_MATCH_SPAN_LABEL $whole.${fraction}s"
}

internal fun autoSyncLowConfidenceMessage(
    offsetMs: Int,
    energyStats: String,
    confidence: Double,
    cuesOnScreen: Boolean,
): String {
    val detail = "Low confidence sync. Offset: ${formatOffsetMessage(offsetMs)} ($energyStats, margin: ${formatMargin(confidence)})."
    return if (cuesOnScreen) detail else "$detail Try a scene with more dialogue."
}

internal fun formatMargin(value: Double): String {
    val sign = if (value < 0.0) "-" else ""
    val scaled = (kotlin.math.abs(value) * 1000.0).toInt().coerceIn(0, 999_999)
    val whole = scaled / 1000
    val fraction = (scaled % 1000).toString().padStart(3, '0')
    return "$sign$whole.$fraction"
}

internal fun formatEnergyStats(samples: List<AudioEnergySample>): String {
    var peak = 0.0
    for (sample in samples) {
        val energy = kotlin.math.abs(sample.energy)
        if (energy > peak) peak = energy
    }
    return "N=${samples.size}, peak=${formatFixed3(peak)}"
}

private fun formatFixed3(value: Double): String {
    val scaled = (kotlin.math.abs(value) * 1000.0).toInt().coerceIn(0, 999_999)
    val whole = scaled / 1000
    val fraction = (scaled % 1000).toString().padStart(3, '0')
    return "$whole.$fraction"
}

internal fun formatOffsetMessage(offsetMs: Int): String {
    val sign = if (offsetMs >= 0) "+" else ""
    val seconds = offsetMs / 1000.0
    val formatted = buildString {
        append(sign)
        append(seconds.toInt())
        append('.')
        val fraction = ((kotlin.math.abs(seconds) % 1.0) * 10).toInt()
        append(fraction)
    }
    return "${formatted}s"
}

/** Subtitle Delay moves only for a confident lag outside the zero band. */
fun SubtitleAutoSyncResult.movesSubtitleDelay(): Boolean = when (this) {
    is SubtitleAutoSyncResult.Success -> offsetMs != 0
    is SubtitleAutoSyncResult.LowConfidence -> false
    is SubtitleAutoSyncResult.Error -> false
}

/**
 * Audio sample with energy/amplitude measurement.
 */
data class AudioEnergySample(
    val timestampMs: Long,
    val energy: Double,
)

/**
 * Result of auto-sync computation.
 */
sealed class SubtitleAutoSyncResult {
    data class Success(val offsetMs: Int, val confidence: Double) : SubtitleAutoSyncResult()
    data class LowConfidence(val offsetMs: Int, val confidence: Double) : SubtitleAutoSyncResult()
    data class Error(val message: String) : SubtitleAutoSyncResult()
}
