package com.nuvio.app.features.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    /** Rival peaks closer than this are the same lobe, not a second match. */
    private const val PEAK_SEPARATION_MS = 500
    /**
     * Smallest Pearson lead that may move Subtitle Delay off 0.
     * A 1-sigma bump over the lag table is not this lead.
     */
    private const val MIN_OFFSET_LEAD = 0.05
    
    /**
     * Compute the optimal subtitle offset by correlating audio energy with subtitle cue onsets.
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
        
        // Cue onsets use the same time grid as the energy envelope.
        val subtitleOnsets = buildSubtitleOnsetSignal(windowedCues, audioEnvelope, SAMPLE_WINDOW_MS)
        
        if (audioEnvelope.size < 10 || subtitleOnsets.size < 10) {
            return SubtitleAutoSyncResult.Error("Insufficient dialogue activity detected")
        }
        
        // Find best offset using cross-correlation
        val bestOffset = findBestOffset(audioEnvelope, subtitleOnsets, MAX_OFFSET_SEARCH_MS, OFFSET_STEP_MS)
            ?: return SubtitleAutoSyncResult.Error("Could not determine reliable offset")

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
        
        // Normalize energy values
        val maxEnergy = points.maxOfOrNull { it.value } ?: 1.0
        return if (maxEnergy > 0) {
            points.map { it.copy(value = it.value / maxEnergy) }
        } else {
            points
        }
    }
    
    /**
     * Spike each cue start on the audio envelope's own bins.
     * Bin [center - window/2, center + window/2) matches the energy window for that point.
     */
    private fun buildSubtitleOnsetSignal(
        cues: List<SubtitleSyncCue>,
        audioEnvelope: List<EnergyPoint>,
        windowMs: Long,
    ): List<EnergyPoint> {
        val halfWindow = windowMs / 2
        val points = audioEnvelope.map { audioPoint ->
            val windowStart = audioPoint.timestampMs - halfWindow
            val windowEnd = windowStart + windowMs
            val onsetStrength = cues
                .filter { it.startTimeMs >= windowStart && it.startTimeMs < windowEnd }
                .sumOf { min(it.text.length, 100) }
                .toDouble()
            EnergyPoint(audioPoint.timestampMs, onsetStrength)
        }

        val maxValue = points.maxOfOrNull { it.value } ?: 1.0
        return if (maxValue > 0) {
            points.map { it.copy(value = it.value / maxValue) }
        } else {
            points
        }
    }
    
    /**
     * Cross-correlate mean-centered energy with cue onsets.
     * The returned offset is 0 unless that lag beats offset 0 and the next peak
     * at least 500ms away. [OffsetMatch.confidence] is that Pearson margin.
     */
    private fun findBestOffset(
        audioEnvelope: List<EnergyPoint>,
        subtitleOnsets: List<EnergyPoint>,
        maxOffsetMs: Long,
        stepMs: Long,
    ): OffsetMatch? {
        val searchOffsets = generateSequence(-maxOffsetMs) { it + stepMs }
            .takeWhile { it <= maxOffsetMs }
            .toList()
        if (searchOffsets.isEmpty()) return null

        val correlations = searchOffsets.map { offset ->
            offset.toInt() to computeCorrelation(audioEnvelope, subtitleOnsets, offset)
        }
        val winner = correlations.maxByOrNull { it.second } ?: return null
        val scoreAtZero = correlations.firstOrNull { it.first == 0 }?.second ?: 0.0
        val rival = bestSeparatedPeak(correlations, winner.first)
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
            val zeroRival = bestSeparatedPeak(correlations, 0)
            OffsetMatch(0, scoreAtZero - zeroRival.second)
        }
    }

    /** Highest score at least [PEAK_SEPARATION_MS] from [fromOffsetMs]. */
    private fun bestSeparatedPeak(
        correlations: List<Pair<Int, Double>>,
        fromOffsetMs: Int,
    ): Pair<Int, Double> {
        return correlations
            .filter { abs(it.first - fromOffsetMs) >= PEAK_SEPARATION_MS }
            .maxByOrNull { it.second }
            ?: (fromOffsetMs to 0.0)
    }

    /**
     * Pearson correlation of the two series on their overlap, after mean-centering both.
     * Positive [offsetMs] shifts cue onsets later so they meet later audio.
     */
    private fun computeCorrelation(
        audio: List<EnergyPoint>,
        subtitles: List<EnergyPoint>,
        offsetMs: Long,
    ): Double {
        if (audio.size < 2 || subtitles.isEmpty()) return 0.0

        val shiftedSubtitles = subtitles.map { it.copy(timestampMs = it.timestampMs + offsetMs) }
        val overlapStart = max(audio.first().timestampMs, shiftedSubtitles.first().timestampMs)
        val overlapEnd = min(audio.last().timestampMs, shiftedSubtitles.last().timestampMs)
        if (overlapStart >= overlapEnd) return 0.0

        val sampleInterval = (audio[1].timestampMs - audio[0].timestampMs).coerceAtLeast(50L)
        val audioValues = ArrayList<Double>()
        val subtitleValues = ArrayList<Double>()
        var time = overlapStart
        while (time <= overlapEnd) {
            audioValues.add(interpolate(audio, time))
            subtitleValues.add(interpolate(shiftedSubtitles, time))
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

/** Subtitle Delay moves only for a lag that beat offset 0. */
fun SubtitleAutoSyncResult.movesSubtitleDelay(): Boolean = when (this) {
    is SubtitleAutoSyncResult.Success -> offsetMs != 0
    is SubtitleAutoSyncResult.LowConfidence -> offsetMs != 0
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
