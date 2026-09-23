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
        
        // Convert subtitle cues to onset signal
        val subtitleOnsets = buildSubtitleOnsetSignal(windowedCues, audioEnvelope.first().timestampMs, audioEnvelope.last().timestampMs, SAMPLE_WINDOW_MS)
        
        if (audioEnvelope.size < 10 || subtitleOnsets.size < 10) {
            return SubtitleAutoSyncResult.Error("Insufficient dialogue activity detected")
        }
        
        // Find best offset using cross-correlation
        val bestOffset = findBestOffset(audioEnvelope, subtitleOnsets, MAX_OFFSET_SEARCH_MS, OFFSET_STEP_MS)
        
        return bestOffset?.let { (offset, confidence) ->
            if (confidence < 0.3) {
                SubtitleAutoSyncResult.LowConfidence(offset, confidence)
            } else {
                SubtitleAutoSyncResult.Success(offset, confidence)
            }
        } ?: SubtitleAutoSyncResult.Error("Could not determine reliable offset")
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
     * Build a subtitle onset signal where each cue start creates a spike.
     */
    private fun buildSubtitleOnsetSignal(
        cues: List<SubtitleSyncCue>,
        startTime: Long,
        endTime: Long,
        windowMs: Long,
    ): List<EnergyPoint> {
        val points = mutableListOf<EnergyPoint>()
        
        var currentTime = startTime
        while (currentTime <= endTime) {
            val windowEnd = currentTime + windowMs
            
            // Count cue onsets in this window and weight by cue text length (longer text = more dialogue)
            val onsetStrength = cues
                .filter { it.startTimeMs >= currentTime && it.startTimeMs < windowEnd }
                .sumOf { min(it.text.length, 100) }
                .toDouble()
            
            points.add(EnergyPoint(currentTime + windowMs / 2, onsetStrength))
            currentTime += windowMs
        }
        
        // Normalize
        val maxValue = points.maxOfOrNull { it.value } ?: 1.0
        return if (maxValue > 0) {
            points.map { it.copy(value = it.value / maxValue) }
        } else {
            points
        }
    }
    
    /**
     * Find the best offset by cross-correlating audio energy with subtitle onsets.
     * Returns offset in ms and confidence score (0-1).
     */
    private fun findBestOffset(
        audioEnvelope: List<EnergyPoint>,
        subtitleOnsets: List<EnergyPoint>,
        maxOffsetMs: Long,
        stepMs: Long,
    ): Pair<Int, Double>? {
        val searchOffsets = generateSequence(-maxOffsetMs) { it + stepMs }
            .takeWhile { it <= maxOffsetMs }
            .toList()
        
        val correlations = searchOffsets.map { offset ->
            val correlation = computeCorrelation(audioEnvelope, subtitleOnsets, offset)
            offset.toInt() to correlation
        }
        
        val best = correlations.maxByOrNull { it.second } ?: return null
        
        // Confidence is based on how much better the best offset is compared to average
        val avgCorrelation = correlations.map { it.second }.average()
        val stdDev = sqrt(correlations.map { (it.second - avgCorrelation) * (it.second - avgCorrelation) }.average())
        val confidence = if (stdDev > 0) {
            ((best.second - avgCorrelation) / stdDev).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        
        return best.first to confidence
    }
    
    /**
     * Compute correlation between audio and shifted subtitle signal.
     */
    private fun computeCorrelation(
        audio: List<EnergyPoint>,
        subtitles: List<EnergyPoint>,
        offsetMs: Long,
    ): Double {
        if (audio.isEmpty() || subtitles.isEmpty()) return 0.0
        
        // Shift subtitle signal by offset
        val shiftedSubtitles = subtitles.map { it.copy(timestampMs = it.timestampMs + offsetMs) }
        
        // Find overlapping time range
        val audioStart = audio.first().timestampMs
        val audioEnd = audio.last().timestampMs
        val subStart = shiftedSubtitles.first().timestampMs
        val subEnd = shiftedSubtitles.last().timestampMs
        
        val overlapStart = max(audioStart, subStart)
        val overlapEnd = min(audioEnd, subEnd)
        
        if (overlapStart >= overlapEnd) return 0.0
        
        // Sample both signals at regular intervals in overlap region
        val sampleInterval = (audio[1].timestampMs - audio[0].timestampMs).coerceAtLeast(50L)
        var sumProduct = 0.0
        var sumAudioSq = 0.0
        var sumSubSq = 0.0
        var sampleCount = 0
        
        var time = overlapStart
        while (time <= overlapEnd) {
            val audioValue = interpolate(audio, time)
            val subValue = interpolate(shiftedSubtitles, time)
            
            sumProduct += audioValue * subValue
            sumAudioSq += audioValue * audioValue
            sumSubSq += subValue * subValue
            sampleCount++
            
            time += sampleInterval
        }
        
        if (sampleCount == 0 || sumAudioSq == 0.0 || sumSubSq == 0.0) return 0.0
        
        // Pearson correlation coefficient
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
