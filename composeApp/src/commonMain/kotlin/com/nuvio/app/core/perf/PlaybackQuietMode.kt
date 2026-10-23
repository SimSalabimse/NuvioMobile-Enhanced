package com.nuvio.app.core.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * While the player is open, poster prefetch and catalog refreshes wait until it closes. Downloads
 * and media analysis are not routed through here: their pacing stays with PlaybackPriority.
 */
internal object PlaybackQuietMode {
    private val lock = SynchronizedObject()
    private var openPlayers = 0
    private val deferredWork = LinkedHashMap<String, () -> Unit>()
    private val _playerOpen = MutableStateFlow(false)
    val playerOpen: StateFlow<Boolean> = _playerOpen.asStateFlow()

    fun playerOpened() {
        synchronized(lock) { openPlayers++ }
        _playerOpen.value = true
    }

    fun playerClosed() {
        val pending = synchronized(lock) {
            openPlayers = (openPlayers - 1).coerceAtLeast(0)
            if (openPlayers > 0) return
            deferredWork.values.toList().also { deferredWork.clear() }
        }
        _playerOpen.value = false
        pending.forEach { it() }
    }

    fun isQuiet(): Boolean =
        _playerOpen.value && PerformanceSettings.isEnabled(PerformanceOptimization.QuietPlayback)

    /** Runs [work] now, or keeps the latest [work] per [key] until the player closes. True when deferred. */
    fun deferWhilePlaying(key: String, work: () -> Unit): Boolean {
        if (!PerformanceSettings.isEnabled(PerformanceOptimization.QuietPlayback)) return false
        val deferred = synchronized(lock) {
            if (openPlayers > 0) deferredWork[key] = work
            openPlayers > 0
        }
        if (deferred) PerformanceMetrics.count(PerformanceCounter.PlaybackWorkDeferred)
        return deferred
    }
}

/**
 * True while poster prefetch should hold off. Collected without lifecycle gating on purpose: the
 * screens asking are usually hidden (not resumed) under the player.
 */
@Composable
internal fun rememberPlaybackQuiet(): Boolean {
    val playerOpen by PlaybackQuietMode.playerOpen.collectAsState()
    val settings by remember {
        PerformanceSettings.ensureLoaded()
        PerformanceSettings.state
    }.collectAsState()
    return playerOpen && (settings[PerformanceOptimization.QuietPlayback] ?: true)
}
