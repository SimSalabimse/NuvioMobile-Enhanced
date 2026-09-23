package com.nuvio.app.features.player.skip

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.AudioEnergySample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session state for progressive segment detection during playback.
 * Accumulates audio energy samples over time and detects segment boundaries.
 */
class ProgressiveSegmentSession {
    
    private val _samples = mutableListOf<AudioEnergySample>()
    private val _detectedSegments = MutableStateFlow<List<DetectedSegment>>(emptyList())
    private var _lastAnalysisTimeMs: Long = 0
    private var _sessionStartMs: Long = 0
    private var _isActive = false
    
    val detectedSegments: StateFlow<List<DetectedSegment>> = _detectedSegments.asStateFlow()
    val isActive: Boolean get() = _isActive
    val sampleCount: Int get() = synchronized(_samples) { _samples.size }
    
    /**
     * Start a new progressive capture session.
     */
    fun startSession(startTimeMs: Long) {
        InAppLogger.info("Player/ProgressiveSegment", "Starting progressive segment session at ${startTimeMs}ms")
        synchronized(_samples) {
            _samples.clear()
        }
        _detectedSegments.value = emptyList()
        _sessionStartMs = startTimeMs
        _lastAnalysisTimeMs = startTimeMs
        _isActive = true
    }
    
    /**
     * Stop the session and return all samples collected.
     */
    fun stopSession(): List<AudioEnergySample> {
        InAppLogger.info("Player/ProgressiveSegment", "Stopping session, collected ${_samples.size} samples")
        _isActive = false
        return synchronized(_samples) {
            _samples.toList()
        }
    }
    
    /**
     * Add new energy samples from ongoing capture.
     */
    fun addSamples(newSamples: List<AudioEnergySample>) {
        if (!_isActive || newSamples.isEmpty()) return
        
        synchronized(_samples) {
            _samples.addAll(newSamples)
        }
        
        InAppLogger.debug("Player/ProgressiveSegment", "Added ${newSamples.size} samples, total: ${_samples.size}")
    }
    
    /**
     * Analyze accumulated samples to detect new segment candidates.
     * Should be called periodically (e.g. every 30 seconds) during playback.
     */
    fun analyzeForSegments(currentTimeMs: Long, durationMs: Long) {
        if (!_isActive) return
        
        // Don't analyze too frequently
        if (currentTimeMs - _lastAnalysisTimeMs < 30_000) return
        
        val samplesCopy = synchronized(_samples) {
            if (_samples.size < 100) return // Need minimum samples
            _samples.toList()
        }
        
        InAppLogger.debug("Player/ProgressiveSegment", "Analyzing ${samplesCopy.size} samples at ${currentTimeMs}ms")
        
        val detected = ProgressiveSegmentDetector.detectCandidateSegments(samplesCopy, durationMs)
        
        if (detected.isNotEmpty()) {
            InAppLogger.info(
                "Player/ProgressiveSegment",
                "Detected ${detected.size} candidate segments, best: ${detected.first().type} " +
                "${detected.first().formatDescription()} conf=${"%.2f".format(detected.first().confidence)}"
            )
            
            // Only notify of new high-confidence segments
            val newSegments = detected.filter { candidate ->
                candidate.confidence > 0.4 && 
                _detectedSegments.value.none { existing ->
                    abs(existing.startSec - candidate.startSec) < 10.0 &&
                    existing.type == candidate.type
                }
            }
            
            if (newSegments.isNotEmpty()) {
                _detectedSegments.value = (_detectedSegments.value + newSegments).sortedByDescending { it.confidence }
                InAppLogger.info("Player/ProgressiveSegment", "Emitting ${newSegments.size} new segments")
            }
        }
        
        _lastAnalysisTimeMs = currentTimeMs
    }
    
    /**
     * Clear session state.
     */
    fun clear() {
        synchronized(_samples) {
            _samples.clear()
        }
        _detectedSegments.value = emptyList()
        _isActive = false
        InAppLogger.debug("Player/ProgressiveSegment", "Session cleared")
    }
    
    private fun abs(value: Double): Double = kotlin.math.abs(value)
}
