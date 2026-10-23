package com.nuvio.app.core.perf

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

internal enum class PerformanceCounter {
    PostersDownsampled,
    AddonCacheHits,
    AddonRequestsShared,
    CacheEvictions,
    PlaybackWorkDeferred,
    NextEpisodePrefetches,
    NextEpisodePrefetchHits,
    ImagesCompressed,
}

internal data class PerformanceSnapshot(
    val launchMs: Long? = null,
    val launchFromCachedHome: Boolean = false,
    val startupPreloadMs: Long? = null,
    val startupPreloadParallel: Boolean = false,
    val lastTimeToStreamsMs: Long? = null,
    val lastTimeToPlaybackMs: Long? = null,
    val counters: Map<PerformanceCounter, Int> = emptyMap(),
)

/** Session-only debug readout behind Settings > Performance; nothing here is persisted. */
internal object PerformanceMetrics {
    private val processMark = TimeSource.Monotonic.markNow()
    private val lock = SynchronizedObject()
    private var pendingStreams: Pair<String, TimeSource.Monotonic.ValueTimeMark>? = null
    private var pendingPlayback: TimeSource.Monotonic.ValueTimeMark? = null
    private val _snapshot = MutableStateFlow(PerformanceSnapshot())
    val snapshot: StateFlow<PerformanceSnapshot> = _snapshot.asStateFlow()

    /** Call from the earliest app entry point; the fallback launch clock starts when this object loads. */
    fun markProcessStart() = Unit

    fun recordLaunchReady(fromCachedHome: Boolean) {
        if (_snapshot.value.launchMs != null) return
        val elapsed = processUptimeMs() ?: processMark.elapsedNow().inWholeMilliseconds
        _snapshot.update { if (it.launchMs != null) it else it.copy(launchMs = elapsed, launchFromCachedHome = fromCachedHome) }
    }

    fun recordStartupPreload(durationMs: Long, parallel: Boolean) {
        _snapshot.update { it.copy(startupPreloadMs = durationMs, startupPreloadParallel = parallel) }
    }

    fun markStreamsRequested(requestToken: String) = synchronized(lock) {
        pendingStreams = requestToken to TimeSource.Monotonic.markNow()
    }

    /** First addon answer with streams for the request started by [markStreamsRequested]. */
    fun recordStreamsReady(requestToken: String) {
        val mark = synchronized(lock) {
            val pending = pendingStreams?.takeIf { it.first == requestToken } ?: return
            pendingStreams = null
            pending.second
        }
        _snapshot.update { it.copy(lastTimeToStreamsMs = mark.elapsedNow().inWholeMilliseconds) }
    }

    fun markPlaybackRequested() = synchronized(lock) {
        pendingPlayback = TimeSource.Monotonic.markNow()
    }

    /** First playing frame after the player route was launched. */
    fun recordPlaybackStarted() {
        val mark = synchronized(lock) {
            val pending = pendingPlayback ?: return
            pendingPlayback = null
            pending
        }
        _snapshot.update { it.copy(lastTimeToPlaybackMs = mark.elapsedNow().inWholeMilliseconds) }
    }

    fun count(counter: PerformanceCounter, by: Int = 1) {
        if (by <= 0) return
        _snapshot.update { it.copy(counters = it.counters + (counter to ((it.counters[counter] ?: 0) + by))) }
    }
}

/** Milliseconds since the OS started this process, when the platform can tell. */
internal expect fun processUptimeMs(): Long?

/** Physical footprint of this process (PSS on Android, phys_footprint on iOS). */
internal expect fun currentProcessMemoryBytes(): Long?
