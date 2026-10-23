package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Performance optimization settings repository.
 * Each optimization can be toggled independently for impact measurement.
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance analysis.
 */
object PerformanceSettingsRepository {
    private val storage: PerformanceSettingsStorage = PerformanceSettingsStoragePlatform
    
    // Default values - all optimizations enabled unless explicitly disabled
    private const val DEFAULT_POSTER_CACHE_SIZE_MB = 100
    private const val DEFAULT_HTTP_CACHE_TTL_SECONDS = 60
    
    // Symbol stripping (build-time toggle)
    private val _symbolStrippingEnabled = MutableStateFlow(false)
    val symbolStrippingEnabled: StateFlow<Boolean> = _symbolStrippingEnabled.asStateFlow()
    
    // Poster downsampling + cache cap
    private val _posterDownsamplingEnabled = MutableStateFlow(true)
    val posterDownsamplingEnabled: StateFlow<Boolean> = _posterDownsamplingEnabled.asStateFlow()
    
    private val _posterCacheSizeMB = MutableStateFlow(DEFAULT_POSTER_CACHE_SIZE_MB)
    val posterCacheSizeMB: StateFlow<Int> = _posterCacheSizeMB.asStateFlow()
    
    // Parallel startup
    private val _parallelStartupEnabled = MutableStateFlow(true)
    val parallelStartupEnabled: StateFlow<Boolean> = _parallelStartupEnabled.asStateFlow()
    
    // Cached home first
    private val _cachedHomeFirstEnabled = MutableStateFlow(true)
    val cachedHomeFirstEnabled: StateFlow<Boolean> = _cachedHomeFirstEnabled.asStateFlow()
    
    // Request deduplication
    private val _requestDedupeEnabled = MutableStateFlow(true)
    val requestDedupeEnabled: StateFlow<Boolean> = _requestDedupeEnabled.asStateFlow()
    
    // Short HTTP caches (with freshness exclusion for TheIntroDB)
    private val _shortHttpCachesEnabled = MutableStateFlow(true)
    val shortHttpCachesEnabled: StateFlow<Boolean> = _shortHttpCachesEnabled.asStateFlow()
    
    private val _httpCacheTTLSeconds = MutableStateFlow(DEFAULT_HTTP_CACHE_TTL_SECONDS)
    val httpCacheTTLSeconds: StateFlow<Int> = _httpCacheTTLSeconds.asStateFlow()
    
    // Capped LRU caches
    private val _cappedLruCachesEnabled = MutableStateFlow(true)
    val cappedLruCachesEnabled: StateFlow<Boolean> = _cappedLruCachesEnabled.asStateFlow()
    
    // Pause prefetch during playback
    private val _pausePrefetchDuringPlaybackEnabled = MutableStateFlow(true)
    val pausePrefetchDuringPlaybackEnabled: StateFlow<Boolean> = _pausePrefetchDuringPlaybackEnabled.asStateFlow()
    
    // Prefetch next episode
    private val _nextEpisodePrefetchEnabled = MutableStateFlow(true)
    val nextEpisodePrefetchEnabled: StateFlow<Boolean> = _nextEpisodePrefetchEnabled.asStateFlow()
    
    // Image compression
    private val _imageCompressionEnabled = MutableStateFlow(true)
    val imageCompressionEnabled: StateFlow<Boolean> = _imageCompressionEnabled.asStateFlow()
    
    private var hasLoaded = false
    
    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }
    
    private fun loadFromDisk() {
        hasLoaded = true
        _symbolStrippingEnabled.value = storage.loadSymbolStrippingEnabled() ?: false
        _posterDownsamplingEnabled.value = storage.loadPosterDownsamplingEnabled() ?: true
        _posterCacheSizeMB.value = storage.loadPosterCacheSizeMB() ?: DEFAULT_POSTER_CACHE_SIZE_MB
        _parallelStartupEnabled.value = storage.loadParallelStartupEnabled() ?: true
        _cachedHomeFirstEnabled.value = storage.loadCachedHomeFirstEnabled() ?: true
        _requestDedupeEnabled.value = storage.loadRequestDedupeEnabled() ?: true
        _shortHttpCachesEnabled.value = storage.loadShortHttpCachesEnabled() ?: true
        _httpCacheTTLSeconds.value = storage.loadHttpCacheTTLSeconds() ?: DEFAULT_HTTP_CACHE_TTL_SECONDS
        _cappedLruCachesEnabled.value = storage.loadCappedLruCachesEnabled() ?: true
        _pausePrefetchDuringPlaybackEnabled.value = storage.loadPausePrefetchDuringPlaybackEnabled() ?: true
        _nextEpisodePrefetchEnabled.value = storage.loadNextEpisodePrefetchEnabled() ?: true
        _imageCompressionEnabled.value = storage.loadImageCompressionEnabled() ?: true
    }
    
    fun setSymbolStrippingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_symbolStrippingEnabled.value == enabled) return
        _symbolStrippingEnabled.value = enabled
        storage.saveSymbolStrippingEnabled(enabled)
    }
    
    fun setPosterDownsamplingEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_posterDownsamplingEnabled.value == enabled) return
        _posterDownsamplingEnabled.value = enabled
        storage.savePosterDownsamplingEnabled(enabled)
    }
    
    fun setPosterCacheSizeMB(sizeMB: Int) {
        ensureLoaded()
        val clamped = sizeMB.coerceIn(10, 500)
        if (_posterCacheSizeMB.value == clamped) return
        _posterCacheSizeMB.value = clamped
        storage.savePosterCacheSizeMB(clamped)
    }
    
    fun setParallelStartupEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_parallelStartupEnabled.value == enabled) return
        _parallelStartupEnabled.value = enabled
        storage.saveParallelStartupEnabled(enabled)
    }
    
    fun setCachedHomeFirstEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_cachedHomeFirstEnabled.value == enabled) return
        _cachedHomeFirstEnabled.value = enabled
        storage.saveCachedHomeFirstEnabled(enabled)
    }
    
    fun setRequestDedupeEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_requestDedupeEnabled.value == enabled) return
        _requestDedupeEnabled.value = enabled
        storage.saveRequestDedupeEnabled(enabled)
    }
    
    fun setShortHttpCachesEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_shortHttpCachesEnabled.value == enabled) return
        _shortHttpCachesEnabled.value = enabled
        storage.saveShortHttpCachesEnabled(enabled)
    }
    
    fun setHttpCacheTTLSeconds(ttlSeconds: Int) {
        ensureLoaded()
        val clamped = ttlSeconds.coerceIn(10, 600)
        if (_httpCacheTTLSeconds.value == clamped) return
        _httpCacheTTLSeconds.value = clamped
        storage.saveHttpCacheTTLSeconds(clamped)
    }
    
    fun setCappedLruCachesEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_cappedLruCachesEnabled.value == enabled) return
        _cappedLruCachesEnabled.value = enabled
        storage.saveCappedLruCachesEnabled(enabled)
    }
    
    fun setPausePrefetchDuringPlaybackEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_pausePrefetchDuringPlaybackEnabled.value == enabled) return
        _pausePrefetchDuringPlaybackEnabled.value = enabled
        storage.savePausePrefetchDuringPlaybackEnabled(enabled)
    }
    
    fun setNextEpisodePrefetchEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_nextEpisodePrefetchEnabled.value == enabled) return
        _nextEpisodePrefetchEnabled.value = enabled
        storage.saveNextEpisodePrefetchEnabled(enabled)
    }
    
    fun setImageCompressionEnabled(enabled: Boolean) {
        ensureLoaded()
        if (_imageCompressionEnabled.value == enabled) return
        _imageCompressionEnabled.value = enabled
        storage.saveImageCompressionEnabled(enabled)
    }
    
    fun clearLocalState() {
        hasLoaded = false
        _symbolStrippingEnabled.value = false
        _posterDownsamplingEnabled.value = true
        _posterCacheSizeMB.value = DEFAULT_POSTER_CACHE_SIZE_MB
        _parallelStartupEnabled.value = true
        _cachedHomeFirstEnabled.value = true
        _requestDedupeEnabled.value = true
        _shortHttpCachesEnabled.value = true
        _httpCacheTTLSeconds.value = DEFAULT_HTTP_CACHE_TTL_SECONDS
        _cappedLruCachesEnabled.value = true
        _pausePrefetchDuringPlaybackEnabled.value = true
        _nextEpisodePrefetchEnabled.value = true
        _imageCompressionEnabled.value = true
    }
}
