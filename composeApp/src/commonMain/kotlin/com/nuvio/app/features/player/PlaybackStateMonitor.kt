package com.nuvio.app.features.player

import com.nuvio.app.features.settings.PerformanceSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Playback state monitor for performance optimizations.
 * Item 7: Pause poster prefetch and refreshes during playback.
 * 
 * CRITICAL: This does NOT affect downloads. Downloads keep their existing
 * smart throttle that protects playback.
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance.
 */
object PlaybackStateMonitor {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }
    
    /**
     * Check if poster operations should be paused.
     * Returns true when playback is active AND the optimization is enabled.
     */
    fun shouldPausePosterOperations(): Boolean {
        PerformanceSettingsRepository.ensureLoaded()
        val pauseEnabled = PerformanceSettingsRepository.pausePrefetchDuringPlaybackEnabled.value
        return pauseEnabled && _isPlaying.value
    }
}
