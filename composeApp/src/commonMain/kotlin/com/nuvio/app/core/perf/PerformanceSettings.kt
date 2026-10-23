package com.nuvio.app.core.perf

import com.nuvio.app.features.downloads.FlagSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Runtime switches for the measurable optimizations. Symbol stripping is build-time, see build-ios-ipa.sh. */
internal enum class PerformanceOptimization(val storageKey: String) {
    PosterDownsampling("perf_poster_downsampling"),
    ParallelStartup("perf_parallel_startup"),
    CachedHomeFirst("perf_cached_home_first"),
    RequestDedupe("perf_request_dedupe"),
    CappedCaches("perf_capped_caches"),
    QuietPlayback("perf_quiet_playback"),
    NextEpisodePrefetch("perf_next_episode_prefetch"),
    ImageCompression("perf_image_compression"),
}

internal object PerformanceSettings {
    private val flags = PerformanceOptimization.entries.associateWith { FlagSetting(it.storageKey, default = true) }
    private val _state = MutableStateFlow<Map<PerformanceOptimization, Boolean>>(emptyMap())
    val state: StateFlow<Map<PerformanceOptimization, Boolean>> = _state.asStateFlow()

    fun ensureLoaded() {
        if (_state.value.isNotEmpty()) return
        val loaded = flags.mapValues { (_, flag) -> flag.load() }
        _state.update { current -> current.ifEmpty { loaded } }
    }

    fun isEnabled(optimization: PerformanceOptimization): Boolean {
        ensureLoaded()
        return _state.value[optimization] ?: true
    }

    fun setEnabled(optimization: PerformanceOptimization, enabled: Boolean) {
        ensureLoaded()
        flags.getValue(optimization).save(enabled)
        _state.update { it + (optimization to enabled) }
    }
}
