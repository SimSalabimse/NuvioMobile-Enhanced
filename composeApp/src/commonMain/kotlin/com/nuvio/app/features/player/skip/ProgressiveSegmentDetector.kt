package com.nuvio.app.features.player.skip

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.AudioEnergySample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Progressive segment detector for identifying intro/recap/credits/preview boundaries
 * during live playback. Analyzes audio energy patterns to detect segment transitions.
 * 
 * This detector can identify segments that occur late in the episode (e.g. intro at 6+ minutes).
 */
object ProgressiveSegmentDetector {
    
    private const val MIN_SEGMENT_DURATION_SEC = 10.0
    private const val MAX_SEGMENT_DURATION_SEC = 150.0
    private const val ENERGY_WINDOW_MS = 500L
    private const val SILENCE_THRESHOLD_MULTIPLIER = 0.3
    private const val HIGH_ENERGY_THRESHOLD_MULTIPLIER = 1.8
    private const val MIN_EDGE_STRENGTH = 0.15
    
    /**
     * Analyze accumulated energy samples to detect candidate segment boundaries.
     * Returns list of detected segments sorted by confidence (highest first).
     */
    fun detectCandidateSegments(
        energySamples: List<AudioEnergySample>,
        durationMs: Long,
    ): List<DetectedSegment> {
        if (energySamples.size < 100) {
            InAppLogger.debug("Player/SegmentDetector", "Insufficient samples: ${energySamples.size}")
            return emptyList()
        }
        
        // Build smoothed energy envelope
        val envelope = buildSmoothedEnvelope(energySamples)
        if (envelope.isEmpty()) return emptyList()
        
        val avgEnergy = envelope.map { it.value }.average()
        val stdDev = sqrt(envelope.map { (it.value - avgEnergy) * (it.value - avgEnergy) }.average())
        
        InAppLogger.debug(
            "Player/SegmentDetector",
            "Analyzing ${envelope.size} envelope points, avg=${"%.3f".format(avgEnergy)} std=${"%.3f".format(stdDev)}"
        )
        
        // Detect edges (significant energy changes)
        val edges = detectEnergyEdges(envelope, avgEnergy, stdDev)
        InAppLogger.debug("Player/SegmentDetector", "Detected ${edges.size} energy edges")
        
        // Group edges into candidate segments
        val candidates = mutableListOf<DetectedSegment>()
        
        // Look for intro/recap patterns: high energy music → dialogue transition
        candidates.addAll(detectMusicToDialogueSegments(envelope, edges, avgEnergy, stdDev))
        
        // Look for outro/credits patterns: dialogue → music/silence
        candidates.addAll(detectDialogueToMusicSegments(envelope, edges, avgEnergy, stdDev, durationMs))
        
        return candidates
            .filter { it.durationSec >= MIN_SEGMENT_DURATION_SEC && it.durationSec <= MAX_SEGMENT_DURATION_SEC }
            .sortedByDescending { it.confidence }
            .take(5)
    }
    
    /**
     * Detect intro/recap-like segments: high-energy opening → lower dialogue energy.
     * Music-heavy intros typically have higher sustained energy than dialogue.
     */
    private fun detectMusicToDialogueSegments(
        envelope: List<EnergyPoint>,
        edges: List<EnergyEdge>,
        avgEnergy: Double,
        stdDev: Double,
    ): List<DetectedSegment> {
        val segments = mutableListOf<DetectedSegment>()
        val highEnergyThreshold = avgEnergy + (stdDev * HIGH_ENERGY_THRESHOLD_MULTIPLIER)
        
        for (i in 0 until edges.size - 1) {
            val startEdge = edges[i]
            
            // Look for a sustained high-energy region (potential music intro)
            if (startEdge.energyBefore < highEnergyThreshold * 0.8) continue
            
            // Find next significant drop (transition to dialogue)
            for (j in i + 1 until edges.size) {
                val endEdge = edges[j]
                
                // Must be a drop
                if (endEdge.direction != EdgeDirection.FALLING) continue
                
                val startSec = startEdge.timestampMs / 1000.0
                val endSec = endEdge.timestampMs / 1000.0
                val duration = endSec - startSec
                
                if (duration < MIN_SEGMENT_DURATION_SEC || duration > MAX_SEGMENT_DURATION_SEC) continue
                
                // Calculate segment energy characteristics
                val segmentPoints = envelope.filter { 
                    it.timestampMs >= startEdge.timestampMs && it.timestampMs <= endEdge.timestampMs 
                }
                if (segmentPoints.isEmpty()) continue
                
                val segmentAvgEnergy = segmentPoints.map { it.value }.average()
                val energyAfterSegment = envelope
                    .filter { it.timestampMs > endEdge.timestampMs && it.timestampMs <= endEdge.timestampMs + 30_000 }
                    .takeIf { it.isNotEmpty() }
                    ?.map { it.value }?.average() ?: avgEnergy
                
                // High segment energy + clear drop to lower post-segment energy = likely intro/recap
                val energyDrop = (segmentAvgEnergy - energyAfterSegment) / (avgEnergy + 0.001)
                val confidence = calculateSegmentConfidence(
                    duration = duration,
                    energyDrop = energyDrop,
                    segmentAvgEnergy = segmentAvgEnergy,
                    globalAvgEnergy = avgEnergy,
                    startPosition = startSec,
                )
                
                if (confidence > 0.3) {
                    // Classify based on position and characteristics
                    val type = when {
                        startSec < 30.0 -> "intro"
                        startSec < 300.0 && duration < 90.0 -> "intro"
                        duration < 60.0 -> "recap"
                        else -> "intro"
                    }
                    
                    segments.add(
                        DetectedSegment(
                            startSec = startSec,
                            endSec = endSec,
                            type = type,
                            confidence = confidence,
                            reason = "music→dialogue transition"
                        )
                    )
                    InAppLogger.info(
                        "Player/SegmentDetector",
                        "Detected $type: ${formatTimestamp(startSec)}→${formatTimestamp(endSec)} " +
                        "conf=${"%.2f".format(confidence)} drop=${"%.2f".format(energyDrop)}"
                    )
                }
                
                break // Found end for this start
            }
        }
        
        return segments
    }
    
    /**
     * Detect outro/credits-like segments: dialogue → ending music/silence.
     * Credits typically appear near the end and have distinct energy patterns.
     */
    private fun detectDialogueToMusicSegments(
        envelope: List<EnergyPoint>,
        edges: List<EnergyEdge>,
        avgEnergy: Double,
        stdDev: Double,
        durationMs: Long,
    ): List<DetectedSegment> {
        val segments = mutableListOf<DetectedSegment>()
        val durationSec = durationMs / 1000.0
        
        // Focus on the last 15 minutes for credits detection
        val creditsSearchStartMs = max(0L, durationMs - 900_000)
        
        for (i in 0 until edges.size - 1) {
            val startEdge = edges[i]
            
            // Must be in the latter part of content
            if (startEdge.timestampMs < creditsSearchStartMs) continue
            
            // Look for transition point
            for (j in i + 1 until edges.size) {
                val endEdge = edges[j]
                
                val startSec = startEdge.timestampMs / 1000.0
                val endSec = min(endEdge.timestampMs / 1000.0, durationSec)
                val duration = endSec - startSec
                
                if (duration < MIN_SEGMENT_DURATION_SEC || duration > MAX_SEGMENT_DURATION_SEC) continue
                
                val segmentPoints = envelope.filter { 
                    it.timestampMs >= startEdge.timestampMs && it.timestampMs <= endEdge.timestampMs 
                }
                if (segmentPoints.isEmpty()) continue
                
                val segmentAvgEnergy = segmentPoints.map { it.value }.average()
                val energyBeforeSegment = envelope
                    .filter { it.timestampMs < startEdge.timestampMs && it.timestampMs >= startEdge.timestampMs - 30_000 }
                    .takeIf { it.isNotEmpty() }
                    ?.map { it.value }?.average() ?: avgEnergy
                
                // Calculate how different the segment is from preceding content
                val energyChange = abs(segmentAvgEnergy - energyBeforeSegment) / (avgEnergy + 0.001)
                val positionFactor = (startSec / durationSec).coerceIn(0.0, 1.0)
                
                val confidence = (energyChange * 0.5 + positionFactor * 0.5) * 
                    (if (duration > 20.0 && duration < 120.0) 1.0 else 0.7)
                
                if (confidence > 0.35) {
                    segments.add(
                        DetectedSegment(
                            startSec = startSec,
                            endSec = endSec,
                            type = "outro",
                            confidence = confidence,
                            reason = "dialogue→ending transition"
                        )
                    )
                    InAppLogger.info(
                        "Player/SegmentDetector",
                        "Detected outro: ${formatTimestamp(startSec)}→${formatTimestamp(endSec)} " +
                        "conf=${"%.2f".format(confidence)} change=${"%.2f".format(energyChange)}"
                    )
                }
                
                break
            }
        }
        
        return segments
    }
    
    /**
     * Build a smoothed energy envelope for analysis.
     */
    private fun buildSmoothedEnvelope(samples: List<AudioEnergySample>): List<EnergyPoint> {
        if (samples.isEmpty()) return emptyList()
        
        val points = mutableListOf<EnergyPoint>()
        val startTime = samples.first().timestampMs
        val endTime = samples.last().timestampMs
        
        var currentTime = startTime
        while (currentTime <= endTime) {
            val windowEnd = currentTime + ENERGY_WINDOW_MS
            val windowSamples = samples.filter { 
                it.timestampMs >= currentTime && it.timestampMs < windowEnd 
            }
            
            if (windowSamples.isNotEmpty()) {
                val avgEnergy = windowSamples.map { it.energy }.average()
                points.add(EnergyPoint(currentTime + ENERGY_WINDOW_MS / 2, avgEnergy))
            }
            
            currentTime += ENERGY_WINDOW_MS
        }
        
        // Apply additional smoothing (moving average)
        return smoothEnvelope(points, windowSize = 3)
    }
    
    private fun smoothEnvelope(points: List<EnergyPoint>, windowSize: Int): List<EnergyPoint> {
        if (points.size < windowSize) return points
        
        return points.mapIndexed { i, point ->
            val start = max(0, i - windowSize / 2)
            val end = min(points.size, i + windowSize / 2 + 1)
            val window = points.subList(start, end)
            val smoothed = window.map { it.value }.average()
            point.copy(value = smoothed)
        }
    }
    
    /**
     * Detect significant energy edges (transitions).
     */
    private fun detectEnergyEdges(
        envelope: List<EnergyPoint>,
        avgEnergy: Double,
        stdDev: Double,
    ): List<EnergyEdge> {
        if (envelope.size < 2) return emptyList()
        
        val edges = mutableListOf<EnergyEdge>()
        val threshold = MIN_EDGE_STRENGTH * stdDev
        
        for (i in 1 until envelope.size) {
            val prev = envelope[i - 1]
            val curr = envelope[i]
            val energyChange = curr.value - prev.value
            
            if (abs(energyChange) > threshold) {
                edges.add(
                    EnergyEdge(
                        timestampMs = curr.timestampMs,
                        energyBefore = prev.value,
                        energyAfter = curr.value,
                        change = energyChange,
                        direction = if (energyChange > 0) EdgeDirection.RISING else EdgeDirection.FALLING
                    )
                )
            }
        }
        
        return edges
    }
    
    private fun calculateSegmentConfidence(
        duration: Double,
        energyDrop: Double,
        segmentAvgEnergy: Double,
        globalAvgEnergy: Double,
        startPosition: Double,
    ): Double {
        // Duration factor: prefer typical segment lengths (20-90s)
        val durationFactor = when {
            duration in 20.0..90.0 -> 1.0
            duration in 15.0..120.0 -> 0.8
            else -> 0.5
        }
        
        // Energy factor: clear transitions score higher
        val energyFactor = (energyDrop.coerceIn(0.0, 2.0) / 2.0)
        
        // Position factor: intros typically early or mid-episode, not at the very start
        val positionFactor = when {
            startPosition < 5.0 -> 0.7 // Too early, might be cold open
            startPosition < 600.0 -> 1.0 // Good intro range
            else -> 0.6 // Late intro (but possible, e.g. Stranger Things)
        }
        
        return (durationFactor * 0.4 + energyFactor * 0.4 + positionFactor * 0.2).coerceIn(0.0, 1.0)
    }
    
    private fun formatTimestamp(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        return "%d:%02d".format(mins, secs)
    }
    
    private data class EnergyPoint(
        val timestampMs: Long,
        val value: Double,
    )
    
    private data class EnergyEdge(
        val timestampMs: Long,
        val energyBefore: Double,
        val energyAfter: Double,
        val change: Double,
        val direction: EdgeDirection,
    )
    
    private enum class EdgeDirection {
        RISING,
        FALLING,
    }
}

/**
 * A detected segment candidate from progressive analysis.
 */
data class DetectedSegment(
    val startSec: Double,
    val endSec: Double,
    val type: String,
    val confidence: Double,
    val reason: String,
) {
    val durationSec: Double get() = endSec - startSec
    
    fun formatDescription(): String {
        val mins = (startSec / 60).toInt()
        val secs = (startSec % 60).toInt()
        val endMins = (endSec / 60).toInt()
        val endSecs = (endSec % 60).toInt()
        return "%d:%02d → %d:%02d".format(mins, secs, endMins, endSecs)
    }
}
