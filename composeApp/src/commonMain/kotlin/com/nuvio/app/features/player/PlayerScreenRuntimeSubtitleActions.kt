package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) {
        SubtitleRepository.clear()
        return
    }
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    SubtitleRepository.fetchAddonSubtitles(type, videoId)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false, preserveLoadingState: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    
    if (!preserveLoadingState) {
        subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    }
    
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = if (preserveLoadingState) subtitleAutoSyncState.isLoading else false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = if (preserveLoadingState) subtitleAutoSyncState.isLoading else false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.performAutomaticSubtitleSync() {
    val subtitle = selectedAddonSubtitle
    if (subtitle == null) {
        subtitleAutoSyncState = subtitleAutoSyncState.copy(
            errorMessage = "Please select an external subtitle first",
        )
        return
    }
    
    // Guard against double-tap - don't start a new sync if already running
    if (subtitleAutoSyncState.isLoading) {
        println("[AutoSync] Sync already in progress, ignoring duplicate request")
        return
    }
    
    // Mark as loading immediately and keep it throughout the entire process
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        isLoading = true, 
        errorMessage = "Capturing audio... (this may take up to 30 seconds)"
    )
    
    // Start loading subtitle cues if needed, but preserve loading state
    if (subtitleAutoSyncState.cues.isEmpty()) {
        loadSubtitleAutoSyncCues(force = true, preserveLoadingState = true)
    }
    
    scope.launch {
        try {
            // Wait for cues to load if needed
            var attempts = 0
            while (subtitleAutoSyncState.cues.isEmpty() && attempts < 50) {
                delay(100)
                attempts++
            }
            
            if (subtitleAutoSyncState.cues.isEmpty()) {
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = "Could not load subtitle cues",
                )
                return@launch
            }
            
            // Update status to show we're capturing
            subtitleAutoSyncState = subtitleAutoSyncState.copy(
                errorMessage = "Capturing audio... (this may take up to 30 seconds)"
            )
            
            // Start audio capture
            val startPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
            playerController?.startAudioEnergyCapture(startPositionMs)
            
            // Capture for 30 seconds or until we have enough data
            val captureTargetMs = 30_000L
            val minCaptureMs = 20_000L
            val startTimeMs = com.nuvio.app.features.streams.epochMs()
            var lastLoggedMs = 0L
            var lastStatusUpdateMs = 0L
            
            while (com.nuvio.app.features.streams.epochMs() - startTimeMs < captureTargetMs) {
                val capturedMs = playerController?.getAudioCaptureDuration() ?: 0L
                val elapsedMs = com.nuvio.app.features.streams.epochMs() - startTimeMs
                
                // Update status message every 3 seconds to show progress
                if (elapsedMs - lastStatusUpdateMs >= 3000) {
                    val secondsElapsed = elapsedMs / 1000
                    subtitleAutoSyncState = subtitleAutoSyncState.copy(
                        errorMessage = "Capturing audio... (${secondsElapsed}s / 30s)"
                    )
                    lastStatusUpdateMs = elapsedMs
                }
                
                // Log progress every 5 seconds for debugging
                if (elapsedMs - lastLoggedMs >= 5000) {
                    println("[AutoSync] Capture progress: ${capturedMs}ms captured after ${elapsedMs}ms elapsed")
                    lastLoggedMs = elapsedMs
                }
                
                if (capturedMs >= minCaptureMs) {
                    println("[AutoSync] Reached target of ${minCaptureMs}ms after ${elapsedMs}ms")
                    break
                }
                delay(500)
            }
            
            // Stop capture and get samples
            val audioSamples = playerController?.stopAudioEnergyCapture() ?: emptyList()
            println("[AutoSync] Received ${audioSamples.size} audio samples from capture")
            
            if (audioSamples.isEmpty()) {
                val reason = playerController?.audioCaptureFailureReason()?.trim().orEmpty()
                val detail = if (reason.isEmpty()) {
                    "The file may not have an audio track or uses an unsupported format."
                } else {
                    reason
                }
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = "Could not capture audio (N=0). $detail",
                )
                return@launch
            }
            
            // Update status to show we're computing
            subtitleAutoSyncState = subtitleAutoSyncState.copy(
                errorMessage = "Computing sync offset..."
            )
            
            // Compute optimal offset
            val result = SubtitleAutoSyncEngine.computeOptimalOffset(
                audioSamples = audioSamples,
                subtitleCues = subtitleAutoSyncState.cues,
                currentPositionMs = startPositionMs,
            )
            
            subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = false)
            
            when (result) {
                is SubtitleAutoSyncResult.Success -> {
                    if (result.movesSubtitleDelay()) {
                        setSubtitleDelay(result.offsetMs)
                    }
                    subtitleAutoSyncState = subtitleAutoSyncState.copy(
                        errorMessage = "Synced! Offset: ${formatOffsetMessage(result.offsetMs)} (${formatEnergyStats(audioSamples)}, ${formatEnergyMatchSpan(audioSamples)}, margin: ${formatMargin(result.confidence)})",
                    )
                }
                is SubtitleAutoSyncResult.LowConfidence -> {
                    subtitleAutoSyncState = subtitleAutoSyncState.copy(
                        errorMessage = "${autoSyncLowConfidenceMessage(
                            offsetMs = result.offsetMs,
                            energyStats = formatEnergyStats(audioSamples),
                            confidence = result.confidence,
                            cuesOnScreen = subtitleAutoSyncState.cues.isNotEmpty(),
                        )} ${formatEnergyMatchSpan(audioSamples)}",
                    )
                }
                is SubtitleAutoSyncResult.Error -> {
                    subtitleAutoSyncState = subtitleAutoSyncState.copy(
                        errorMessage = result.message,
                    )
                }
                null -> {
                    subtitleAutoSyncState = subtitleAutoSyncState.copy(
                        errorMessage = "Could not determine sync offset",
                    )
                }
            }
        } catch (e: Exception) {
            subtitleAutoSyncState = subtitleAutoSyncState.copy(
                isLoading = false,
                errorMessage = "Auto-sync failed: ${e.message}",
            )
        }
    }
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}
