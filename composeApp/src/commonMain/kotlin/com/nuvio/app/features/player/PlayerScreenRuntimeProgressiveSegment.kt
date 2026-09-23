package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.skip.DetectedSegment
import com.nuvio.app.features.player.skip.ProgressiveSegmentSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Start progressive segment detection session for the current playback.
 * Should be called when playback starts and progressive detection is enabled.
 */
fun PlayerScreenRuntime.startProgressiveSegmentDetection() {
    val settings = playerSettingsUiState
    if (!settings.progressiveSegmentDetectionEnabled) return
    if (progressiveSegmentSession != null) return
    if (contentType == "live") return
    
    val currentPosMs = playbackSnapshot.currentPositionMs
    InAppLogger.info("Player/ProgressiveSegment", "Starting progressive segment detection at ${currentPosMs}ms")
    
    val session = ProgressiveSegmentSession()
    session.startSession(currentPosMs)
    progressiveSegmentSession = session
    
    startProgressiveCaptureOnController(currentPosMs)
    startPeriodicSegmentAnalysis()
    
    scope.launch {
        session.detectedSegments.collect { segments ->
            handleDetectedSegments(segments)
        }
    }
}

/**
 * Stop progressive segment detection and clean up resources.
 */
fun PlayerScreenRuntime.stopProgressiveSegmentDetection() {
    progressiveSegmentAnalysisJob?.cancel()
    progressiveSegmentAnalysisJob = null
    
    stopProgressiveCaptureOnController()
    
    progressiveSegmentSession?.clear()
    progressiveSegmentSession = null
    showDetectedSegmentNotification = false
    currentDetectedSegment = null
    
    InAppLogger.info("Player/ProgressiveSegment", "Stopped progressive segment detection")
}

/**
 * Start platform-specific progressive audio capture.
 */
private fun PlayerScreenRuntime.startProgressiveCaptureOnController(startTimeMs: Long) {
    val controller = playerController ?: return
    
    scope.launch {
        try {
            controller.startProgressiveAudioCapture(startTimeMs)
            InAppLogger.info("Player/ProgressiveSegment", "Started progressive audio capture")
        } catch (e: Exception) {
            InAppLogger.warn("Player/ProgressiveSegment", "Failed to start progressive capture: ${e.message}")
        }
    }
}

/**
 * Stop platform-specific progressive audio capture.
 */
private fun PlayerScreenRuntime.stopProgressiveCaptureOnController() {
    val controller = playerController ?: return
    
    scope.launch {
        try {
            controller.stopProgressiveAudioCapture()
        } catch (e: Exception) {
            InAppLogger.debug("Player/ProgressiveSegment", "Error stopping capture: ${e.message}")
        }
    }
}

/**
 * Start periodic analysis of accumulated audio samples.
 * Runs every 30 seconds to detect new segment boundaries.
 */
private fun PlayerScreenRuntime.startPeriodicSegmentAnalysis() {
    progressiveSegmentAnalysisJob?.cancel()
    
    progressiveSegmentAnalysisJob = scope.launch {
        while (isActive) {
            delay(30_000) // Analyze every 30 seconds
            
            if (!isActive) break
            
            val session = progressiveSegmentSession ?: break
            val controller = playerController ?: continue
            
            try {
                val newSamples = controller.getProgressiveAudioSamples()
                if (newSamples.isNotEmpty()) {
                    session.addSamples(newSamples)
                    
                    val currentPos = playbackSnapshot.currentPositionMs
                    val duration = playbackSnapshot.durationMs
                    
                    if (duration > 0) {
                        session.analyzeForSegments(currentPos, duration)
                    }
                }
            } catch (e: Exception) {
                InAppLogger.debug("Player/ProgressiveSegment", "Analysis cycle error: ${e.message}")
            }
        }
    }
}

/**
 * Handle newly detected segment candidates from the session.
 * Shows notification to user for high-confidence segments.
 */
private fun PlayerScreenRuntime.handleDetectedSegments(segments: List<DetectedSegment>) {
    if (segments.isEmpty()) return
    
    val topSegment = segments.firstOrNull() ?: return
    
    if (currentDetectedSegment?.let { existing ->
        topSegment.type == existing.type && 
        kotlin.math.abs(topSegment.startSec - existing.startSec) < 10.0
    } == true) {
        return
    }
    
    currentDetectedSegment = topSegment
    showDetectedSegmentNotification = true
    
    InAppLogger.info(
        "Player/ProgressiveSegment",
        "Showing notification for ${topSegment.type}: ${topSegment.formatDescription()} " +
        "conf=${"%.2f".format(topSegment.confidence)}"
    )
}

/**
 * Open submit intro dialog pre-filled with detected segment times.
 */
fun PlayerScreenRuntime.openDetectedSegmentForSubmit() {
    val segment = currentDetectedSegment ?: return
    
    submitIntroSegmentType = segment.type
    submitIntroStartTimeStr = formatSecondsToHMS(segment.startSec)
    submitIntroEndTimeStr = formatSecondsToHMS(segment.endSec)
    showSubmitIntroModal = true
    showDetectedSegmentNotification = false
    
    InAppLogger.info("Player/ProgressiveSegment", "Opened submit dialog for detected ${segment.type}")
}

/**
 * Dismiss the detected segment notification.
 */
fun PlayerScreenRuntime.dismissDetectedSegmentNotification() {
    showDetectedSegmentNotification = false
}

private fun formatSecondsToHMS(seconds: Double): String {
    val totalSecs = seconds.toInt()
    val hours = totalSecs / 3600
    val mins = (totalSecs % 3600) / 60
    val secs = totalSecs % 60
    
    return if (hours > 0) {
        "$hours:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    } else {
        "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    }
}
