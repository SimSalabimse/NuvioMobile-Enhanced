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

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
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
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
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
    
    // Start loading subtitle cues if needed
    if (subtitleAutoSyncState.cues.isEmpty()) {
        loadSubtitleAutoSyncCues(force = true)
    }
    
    // Mark as loading
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    
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
            
            // Start audio capture
            val startPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L)
            playerController?.startAudioEnergyCapture(startPositionMs)
            
            // Capture for 30 seconds
            val captureTargetMs = 30_000L
            val startTimeMs = com.nuvio.app.features.streams.epochMs()
            
            while (com.nuvio.app.features.streams.epochMs() - startTimeMs < captureTargetMs) {
                val capturedMs = playerController?.getAudioCaptureDuration() ?: 0L
                if (capturedMs >= 20_000L) {
                    break
                }
                delay(500)
            }
            
            // Stop capture and get samples
            val audioSamples = playerController?.stopAudioEnergyCapture() ?: emptyList()
            
            if (audioSamples.isEmpty()) {
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = "Could not capture audio data. ReplayKit may not be available or MPV audio routing issue. Check logs for details.",
                )
                return@launch
            }
            
            // Compute optimal offset
            val result = SubtitleAutoSyncEngine.computeOptimalOffset(
                audioSamples = audioSamples,
                subtitleCues = subtitleAutoSyncState.cues,
                currentPositionMs = startPositionMs,
            )
            
            subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = false)
            
            when (result) {
                is SubtitleAutoSyncResult.Success -> {
                    setSubtitleDelay(result.offsetMs)
                    subtitleAutoSyncState = subtitleAutoSyncState.copy(
                        errorMessage = "Synced! Offset: ${formatOffsetMessage(result.offsetMs)} (confidence: ${(result.confidence * 100).toInt()}%)",
                    )
                }
                is SubtitleAutoSyncResult.LowConfidence -> {
                    setSubtitleDelay(result.offsetMs)
                    subtitleAutoSyncState = subtitleAutoSyncState.copy(
                        errorMessage = "Low confidence sync. Offset: ${formatOffsetMessage(result.offsetMs)}. Try a scene with more dialogue.",
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

private fun formatOffsetMessage(offsetMs: Int): String {
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

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}
