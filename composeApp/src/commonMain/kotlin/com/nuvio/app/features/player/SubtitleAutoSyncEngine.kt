package com.nuvio.app.features.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Automatic subtitle synchronization engine using speech activity detection.
 * 
 * This engine correlates audio energy patterns with subtitle cue timing to find
 * the optimal subtitle offset, regardless of subtitle/audio language mismatch.
 */
object SubtitleAutoSyncEngine {
    
    private const val SAMPLE_WINDOW_MS = 100L
    private const val MIN_ANALYSIS_DURATION_MS = 15_000L
    private const val MAX_ANALYSIS_DURATION_MS = 60_000L
    private const val MAX_OFFSET_SEARCH_MS = 10_000L
    private const val OFFSET_STEP_MS = 100L
    /**
     * Smallest Pearson lead that may move Subtitle Delay off 0.
     * The same drop also ends a correlation lobe, so the shoulder of a
     * dialogue-length match is not a second peak.
     */
    private const val MIN_OFFSET_LEAD = 0.05
    /** Raised-cosine taper at each end of a cue in the speech-mask pass. */
    private const val SPEECH_CUE_EDGE = 0.08
    /** Quiet-bed percentile removed before the speech mask is scaled. */
    private const val SPEECH_FLOOR_PERCENTILE = 0.35
    /** Scale the mask from this percentile so one hit cannot flatten speech. */
    private const val SPEECH_SCALE_PERCENTILE = 0.75
    /** 100ms bins on either side. A 7-bin average turns bursts into the line. */
    private const val SPEECH_SMOOTH_RADIUS = 3
    /**
     * A lag near the ±10s cap is a shorter overlap. This penalty keeps that
     * edge from beating the same dialogue pattern one period away.
     */
    private const val SPEECH_LAG_PENALTY = 0.08
    
    /**
     * Compute the optimal subtitle offset by correlating audio energy with subtitle cue activity.
     * 
     * @param audioSamples Audio amplitude samples with timestamps
     * @param subtitleCues Subtitle cues with timing information
     * @param currentPositionMs Current playback position to center the analysis around
     * @return Suggested offset in milliseconds, or null if sync cannot be determined
     */
    fun computeOptimalOffset(
        audioSamples: List<AudioEnergySample>,
        subtitleCues: List<SubtitleSyncCue>,
        currentPositionMs: Long,
    ): SubtitleAutoSyncResult? {
        if (audioSamples.isEmpty() || subtitleCues.isEmpty()) {
            return SubtitleAutoSyncResult.Error("Insufficient data for auto-sync")
        }
        
        // Define analysis window around current position
        val windowStart = max(0L, currentPositionMs - MAX_ANALYSIS_DURATION_MS / 2)
        val windowEnd = currentPositionMs + MAX_ANALYSIS_DURATION_MS / 2
        
        // Filter data to analysis window
        val windowedAudio = audioSamples.filter { it.timestampMs in windowStart..windowEnd }
        val windowedCues = subtitleCues.filter { it.startTimeMs in windowStart..windowEnd }
        
        if (windowedAudio.isEmpty() || windowedCues.isEmpty()) {
            return SubtitleAutoSyncResult.Error("No subtitle or audio data in analysis window")
        }
        
        val duration = windowedAudio.maxOf { it.timestampMs } - windowedAudio.minOf { it.timestampMs }
        if (duration < MIN_ANALYSIS_DURATION_MS) {
            return SubtitleAutoSyncResult.Error("Need at least ${MIN_ANALYSIS_DURATION_MS / 1000}s of dialogue for reliable sync")
        }
        
        // Convert audio to energy envelope
        val audioEnvelope = buildAudioEnergyEnvelope(windowedAudio, SAMPLE_WINDOW_MS)
        
        // Cue activity uses the same time grid as the energy envelope.
        val subtitleActivity = buildSubtitleActivitySignal(windowedCues, audioEnvelope, SAMPLE_WINDOW_MS)
        
        if (audioEnvelope.size < 10 || subtitleActivity.size < 10 || subtitleActivity.none { it.value > 0.0 }) {
            val audioStart = windowedAudio.minOf { it.timestampMs }
            val audioEnd = windowedAudio.maxOf { it.timestampMs }
            return SubtitleAutoSyncResult.Error(
                "Captured audio does not overlap these cues (${formatEnergyStats(windowedAudio)}, audio ${audioStart}-${audioEnd}ms)",
            )
        }
        
        // The cosine template resolves the opening lines. Real speech is flatter,
        // and a repeating cadence then ties an edge lag (the +9.6s miss) with
        // the delay at the playhead. The speech mask picks that delay when it
        // leads by more.
        val shapeMatch = findBestOffset(audioEnvelope, subtitleActivity, MAX_OFFSET_SEARCH_MS, OFFSET_STEP_MS)
        val speechEnvelope = buildSpeechEnergyEnvelope(windowedAudio, SAMPLE_WINDOW_MS)
        val speechActivity = buildSpeechCueSignal(windowedCues, speechEnvelope, SAMPLE_WINDOW_MS)
        val speechMatch = if (speechEnvelope.size >= 10 && speechActivity.any { it.value > 0.0 }) {
            findBestOffset(
                speechEnvelope,
                speechActivity,
                MAX_OFFSET_SEARCH_MS,
                OFFSET_STEP_MS,
                zerosOutside = true,
                lagPenalty = SPEECH_LAG_PENALTY,
            )
        } else {
            null
        }
        val bestOffset = when {
            shapeMatch == null -> speechMatch
            speechMatch == null -> shapeMatch
            speechMatch.confidence > shapeMatch.confidence -> speechMatch
            else -> shapeMatch
        } ?: return SubtitleAutoSyncResult.Error("Could not determine reliable offset")

        // 0.3 is a correlation margin, not a z-score. A 1-sigma lead stays well below 1.
        return if (bestOffset.confidence < 0.3) {
            SubtitleAutoSyncResult.LowConfidence(bestOffset.offsetMs, bestOffset.confidence)
        } else {
            SubtitleAutoSyncResult.Success(bestOffset.offsetMs, bestOffset.confidence)
        }
    }
    
    /**
     * Build an audio energy envelope by averaging energy in time windows.
     */
    private fun buildAudioEnergyEnvelope(
        samples: List<AudioEnergySample>,
        windowMs: Long,
    ): List<EnergyPoint> {
        val points = buildRawEnergyEnvelope(samples, windowMs)
        val maxEnergy = points.maxOfOrNull { it.value } ?: 1.0
        return if (maxEnergy > 0) {
            points.map { it.copy(value = it.value / maxEnergy) }
        } else {
            points
        }
    }

    /**
     * Speech mask for dialogue that is not a raised cosine. The quiet bed is
     * removed, one loud hit cannot own the scale, and a short average fills
     * the gaps inside a line.
     */
    private fun buildSpeechEnergyEnvelope(
        samples: List<AudioEnergySample>,
        windowMs: Long,
    ): List<EnergyPoint> {
        val raw = buildRawEnergyEnvelope(samples, windowMs)
        if (raw.isEmpty()) return raw
        val floor = percentile(raw.map { it.value }, SPEECH_FLOOR_PERCENTILE)
        val lifted = raw.map { it.copy(value = max(0.0, it.value - floor)) }
        val scale = percentile(lifted.map { it.value }, SPEECH_SCALE_PERCENTILE)
        val scaled = if (scale > 0.0) {
            lifted.map { it.copy(value = min(1.5, it.value / scale)) }
        } else {
            lifted
        }
        return smooth(scaled, SPEECH_SMOOTH_RADIUS)
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
            val windowSamples = samples.filter { it.timestampMs >= windowStart && it.timestampMs < windowEnd }

            if (windowSamples.isNotEmpty()) {
                val avgEnergy = windowSamples.map { it.energy }.average()
                points.add(EnergyPoint(windowStart + windowMs / 2, avgEnergy))
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

    private fun smooth(points: List<EnergyPoint>, radius: Int): List<EnergyPoint> {
        if (radius <= 0 || points.size < 2) return points
        return points.mapIndexed { index, point ->
            var sum = 0.0
            var count = 0
            for (cursor in (index - radius)..(index + radius)) {
                if (cursor in points.indices) {
                    sum += points[cursor].value
                    count += 1
                }
            }
            point.copy(value = if (count > 0) sum / count else point.value)
        }
    }
    
    /**
     * Speech is quiet at the edges of a line and loud in the middle. A flat
     * rectangle still matches when one line slides onto the next, so two opening
     * cues cannot clear the margin. A raised cosine over the cue does.
     * Bin [center - window/2, center + window/2) matches the energy window.
     */
    private fun buildSubtitleActivitySignal(
        cues: List<SubtitleSyncCue>,
        audioEnvelope: List<EnergyPoint>,
        windowMs: Long,
    ): List<EnergyPoint> {
        val halfWindow = windowMs / 2
        return audioEnvelope.map { audioPoint ->
            val windowStart = audioPoint.timestampMs - halfWindow
            val windowEnd = windowStart + windowMs
            var level = 0.0
            for (cue in cues) {
                val cueEnd = if (cue.endTimeMs > cue.startTimeMs) cue.endTimeMs else cue.startTimeMs + windowMs
                if (cue.startTimeMs >= windowEnd || cueEnd <= windowStart) continue
                val length = (cueEnd - cue.startTimeMs).coerceAtLeast(1L).toDouble()
                val progress = ((audioPoint.timestampMs - cue.startTimeMs).toDouble() / length).coerceIn(0.0, 1.0)
                val wave = sin(PI * progress)
                val shaped = wave * wave
                if (shaped > level) level = shaped
            }
            EnergyPoint(audioPoint.timestampMs, level)
        }
    }

    /**
     * High for the whole line, with a short taper so a one-line slide still
     * falls off. Bursts anywhere in the cue count, which a center-weighted
     * cosine misses.
     */
    private fun buildSpeechCueSignal(
        cues: List<SubtitleSyncCue>,
        audioEnvelope: List<EnergyPoint>,
        windowMs: Long,
    ): List<EnergyPoint> {
        val halfWindow = windowMs / 2
        return audioEnvelope.map { audioPoint ->
            val windowStart = audioPoint.timestampMs - halfWindow
            val windowEnd = windowStart + windowMs
            var level = 0.0
            for (cue in cues) {
                val cueEnd = if (cue.endTimeMs > cue.startTimeMs) cue.endTimeMs else cue.startTimeMs + windowMs
                if (cue.startTimeMs >= windowEnd || cueEnd <= windowStart) continue
                val length = (cueEnd - cue.startTimeMs).coerceAtLeast(1L).toDouble()
                val progress = ((audioPoint.timestampMs - cue.startTimeMs).toDouble() / length).coerceIn(0.0, 1.0)
                val shaped = if (progress < SPEECH_CUE_EDGE) {
                    0.5 * (1.0 - cos(PI * progress / SPEECH_CUE_EDGE))
                } else if (progress > 1.0 - SPEECH_CUE_EDGE) {
                    0.5 * (1.0 - cos(PI * (1.0 - progress) / SPEECH_CUE_EDGE))
                } else {
                    1.0
                }
                if (shaped > level) level = shaped
            }
            EnergyPoint(audioPoint.timestampMs, level)
        }
    }
    
    /**
     * Cross-correlate mean-centered energy with cue activity.
     * The returned offset is 0 unless that lag beats offset 0 and the next peak
     * on another lobe. [OffsetMatch.confidence] is that Pearson margin.
     */
    private fun findBestOffset(
        audioEnvelope: List<EnergyPoint>,
        subtitleActivity: List<EnergyPoint>,
        maxOffsetMs: Long,
        stepMs: Long,
        zerosOutside: Boolean = false,
        lagPenalty: Double = 0.0,
    ): OffsetMatch? {
        val searchOffsets = generateSequence(-maxOffsetMs) { it + stepMs }
            .takeWhile { it <= maxOffsetMs }
            .toList()
        if (searchOffsets.isEmpty()) return null

        val penaltyScale = maxOffsetMs.toDouble().coerceAtLeast(1.0)
        val correlations = searchOffsets.map { offset ->
            val raw = computeCorrelation(audioEnvelope, subtitleActivity, offset, zerosOutside)
            val penalized = raw - lagPenalty * (abs(offset).toDouble() / penaltyScale)
            offset.toInt() to penalized
        }
        val winner = correlations.maxByOrNull { it.second } ?: return null
        val scoreAtZero = correlations.firstOrNull { it.first == 0 }?.second ?: 0.0
        val rival = bestDistinctPeak(correlations, winner.first)
        val leadOverZero = winner.second - scoreAtZero
        val leadOverRival = winner.second - rival.second

        // Offset 0 stays when it is within the winner's lead over the next peak.
        val beatsZero = winner.first != 0 &&
            leadOverZero > MIN_OFFSET_LEAD &&
            leadOverRival > MIN_OFFSET_LEAD &&
            leadOverZero > leadOverRival * 0.5
        return if (beatsZero) {
            OffsetMatch(winner.first, min(leadOverZero, leadOverRival))
        } else {
            val zeroRival = bestDistinctPeak(correlations, 0)
            OffsetMatch(0, scoreAtZero - zeroRival.second)
        }
    }

    /**
     * Highest score on a later lobe. The walk leaves the anchor only after the
     * score falls by [MIN_OFFSET_LEAD] and then climbs by that same lead, so the
     * shoulder of this match is not the rival.
     */
    private fun bestDistinctPeak(
        correlations: List<Pair<Int, Double>>,
        fromOffsetMs: Int,
    ): Pair<Int, Double> {
        val ordered = correlations.sortedBy { it.first }
        val anchorIndex = ordered.indexOfFirst { it.first == fromOffsetMs }
        if (anchorIndex < 0) return fromOffsetMs to 0.0
        val anchorScore = ordered[anchorIndex].second
        val left = maxAfterValley(ordered, anchorIndex, anchorScore, -1)
        val right = maxAfterValley(ordered, anchorIndex, anchorScore, 1)
        return listOfNotNull(left, right).maxByOrNull { it.second } ?: (fromOffsetMs to 0.0)
    }

    private fun maxAfterValley(
        ordered: List<Pair<Int, Double>>,
        anchorIndex: Int,
        anchorScore: Double,
        direction: Int,
    ): Pair<Int, Double>? {
        var index = anchorIndex
        var valley = anchorScore
        var crossed = false
        var best: Pair<Int, Double>? = null
        while (true) {
            index += direction
            if (index !in ordered.indices) break
            val point = ordered[index]
            if (!crossed) {
                if (point.second < valley) valley = point.second
                if (anchorScore - valley >= MIN_OFFSET_LEAD && point.second >= valley + MIN_OFFSET_LEAD) {
                    crossed = true
                    best = point
                }
            } else if (best == null || point.second > best.second) {
                best = point
            }
        }
        return best
    }

    /**
     * Pearson correlation of the two series on their overlap, after mean-centering both.
     * Positive [offsetMs] shifts cue activity later so it meets later audio.
     */
    private fun computeCorrelation(
        audio: List<EnergyPoint>,
        subtitles: List<EnergyPoint>,
        offsetMs: Long,
        zerosOutside: Boolean = false,
    ): Double {
        if (audio.size < 2 || subtitles.isEmpty()) return 0.0

        val shiftedSubtitles = subtitles.map { it.copy(timestampMs = it.timestampMs + offsetMs) }
        val overlapStart: Long
        val overlapEnd: Long
        if (zerosOutside) {
            // Same samples at every lag. A short overlap cannot outscore the
            // lines that actually sit on the speech.
            overlapStart = audio.first().timestampMs
            overlapEnd = audio.last().timestampMs
        } else {
            overlapStart = max(audio.first().timestampMs, shiftedSubtitles.first().timestampMs)
            overlapEnd = min(audio.last().timestampMs, shiftedSubtitles.last().timestampMs)
        }
        if (overlapStart >= overlapEnd) return 0.0

        val sampleInterval = (audio[1].timestampMs - audio[0].timestampMs).coerceAtLeast(50L)
        val audioValues = ArrayList<Double>()
        val subtitleValues = ArrayList<Double>()
        var time = overlapStart
        while (time <= overlapEnd) {
            audioValues.add(interpolate(audio, time))
            subtitleValues.add(
                if (zerosOutside &&
                    (time < shiftedSubtitles.first().timestampMs || time > shiftedSubtitles.last().timestampMs)
                ) {
                    0.0
                } else {
                    interpolate(shiftedSubtitles, time)
                },
            )
            time += sampleInterval
        }
        if (audioValues.size < 2) return 0.0

        val audioMean = audioValues.sum() / audioValues.size
        val subtitleMean = subtitleValues.sum() / subtitleValues.size
        var sumProduct = 0.0
        var sumAudioSq = 0.0
        var sumSubSq = 0.0
        for (index in audioValues.indices) {
            val centeredAudio = audioValues[index] - audioMean
            val centeredSubtitle = subtitleValues[index] - subtitleMean
            sumProduct += centeredAudio * centeredSubtitle
            sumAudioSq += centeredAudio * centeredAudio
            sumSubSq += centeredSubtitle * centeredSubtitle
        }
        if (sumAudioSq == 0.0 || sumSubSq == 0.0) return 0.0
        return sumProduct / sqrt(sumAudioSq * sumSubSq)
    }
    
    /**
     * Linear interpolation to get signal value at arbitrary timestamp.
     */
    private fun interpolate(points: List<EnergyPoint>, timestampMs: Long): Double {
        if (points.isEmpty()) return 0.0
        if (timestampMs <= points.first().timestampMs) return points.first().value
        if (timestampMs >= points.last().timestampMs) return points.last().value
        
        // Find surrounding points
        val rightIdx = points.indexOfFirst { it.timestampMs > timestampMs }
        if (rightIdx <= 0) return points.first().value
        
        val left = points[rightIdx - 1]
        val right = points[rightIdx]
        
        val fraction = (timestampMs - left.timestampMs).toDouble() / (right.timestampMs - left.timestampMs)
        return left.value + fraction * (right.value - left.value)
    }
    
    private data class EnergyPoint(
        val timestampMs: Long,
        val value: Double,
    )

    private data class OffsetMatch(
        val offsetMs: Int,
        val confidence: Double,
    )
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

/** Subtitle Delay moves only for a confident lag that beat offset 0. */
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
