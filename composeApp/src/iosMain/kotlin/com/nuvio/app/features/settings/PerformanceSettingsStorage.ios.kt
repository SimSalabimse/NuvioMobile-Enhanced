package com.nuvio.app.features.settings

import platform.Foundation.NSUserDefaults

internal actual object PerformanceSettingsStoragePlatform : PerformanceSettingsStorage {
    private const val KEY_SYMBOL_STRIPPING = "performance_symbol_stripping_enabled"
    private const val KEY_POSTER_DOWNSAMPLING = "performance_poster_downsampling_enabled"
    private const val KEY_POSTER_CACHE_SIZE_MB = "performance_poster_cache_size_mb"
    private const val KEY_PARALLEL_STARTUP = "performance_parallel_startup_enabled"
    private const val KEY_CACHED_HOME_FIRST = "performance_cached_home_first_enabled"
    private const val KEY_REQUEST_DEDUPE = "performance_request_dedupe_enabled"
    private const val KEY_SHORT_HTTP_CACHES = "performance_short_http_caches_enabled"
    private const val KEY_HTTP_CACHE_TTL = "performance_http_cache_ttl_seconds"
    private const val KEY_CAPPED_LRU_CACHES = "performance_capped_lru_caches_enabled"
    private const val KEY_PAUSE_PREFETCH_PLAYBACK = "performance_pause_prefetch_playback_enabled"
    private const val KEY_NEXT_EPISODE_PREFETCH = "performance_next_episode_prefetch_enabled"
    private const val KEY_IMAGE_COMPRESSION = "performance_image_compression_enabled"
    
    private val defaults get() = NSUserDefaults.standardUserDefaults
    
    override fun loadSymbolStrippingEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_SYMBOL_STRIPPING) != null)
            defaults.boolForKey(KEY_SYMBOL_STRIPPING)
        else null
    
    override fun saveSymbolStrippingEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_SYMBOL_STRIPPING)
    }
    
    override fun loadPosterDownsamplingEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_POSTER_DOWNSAMPLING) != null)
            defaults.boolForKey(KEY_POSTER_DOWNSAMPLING)
        else null
    
    override fun savePosterDownsamplingEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_POSTER_DOWNSAMPLING)
    }
    
    override fun loadPosterCacheSizeMB(): Int? =
        if (defaults.objectForKey(KEY_POSTER_CACHE_SIZE_MB) != null)
            defaults.integerForKey(KEY_POSTER_CACHE_SIZE_MB).toInt()
        else null
    
    override fun savePosterCacheSizeMB(sizeMB: Int) {
        defaults.setInteger(sizeMB.toLong(), KEY_POSTER_CACHE_SIZE_MB)
    }
    
    override fun loadParallelStartupEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_PARALLEL_STARTUP) != null)
            defaults.boolForKey(KEY_PARALLEL_STARTUP)
        else null
    
    override fun saveParallelStartupEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_PARALLEL_STARTUP)
    }
    
    override fun loadCachedHomeFirstEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_CACHED_HOME_FIRST) != null)
            defaults.boolForKey(KEY_CACHED_HOME_FIRST)
        else null
    
    override fun saveCachedHomeFirstEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_CACHED_HOME_FIRST)
    }
    
    override fun loadRequestDedupeEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_REQUEST_DEDUPE) != null)
            defaults.boolForKey(KEY_REQUEST_DEDUPE)
        else null
    
    override fun saveRequestDedupeEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_REQUEST_DEDUPE)
    }
    
    override fun loadShortHttpCachesEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_SHORT_HTTP_CACHES) != null)
            defaults.boolForKey(KEY_SHORT_HTTP_CACHES)
        else null
    
    override fun saveShortHttpCachesEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_SHORT_HTTP_CACHES)
    }
    
    override fun loadHttpCacheTTLSeconds(): Int? =
        if (defaults.objectForKey(KEY_HTTP_CACHE_TTL) != null)
            defaults.integerForKey(KEY_HTTP_CACHE_TTL).toInt()
        else null
    
    override fun saveHttpCacheTTLSeconds(ttlSeconds: Int) {
        defaults.setInteger(ttlSeconds.toLong(), KEY_HTTP_CACHE_TTL)
    }
    
    override fun loadCappedLruCachesEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_CAPPED_LRU_CACHES) != null)
            defaults.boolForKey(KEY_CAPPED_LRU_CACHES)
        else null
    
    override fun saveCappedLruCachesEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_CAPPED_LRU_CACHES)
    }
    
    override fun loadPausePrefetchDuringPlaybackEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_PAUSE_PREFETCH_PLAYBACK) != null)
            defaults.boolForKey(KEY_PAUSE_PREFETCH_PLAYBACK)
        else null
    
    override fun savePausePrefetchDuringPlaybackEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_PAUSE_PREFETCH_PLAYBACK)
    }
    
    override fun loadNextEpisodePrefetchEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_NEXT_EPISODE_PREFETCH) != null)
            defaults.boolForKey(KEY_NEXT_EPISODE_PREFETCH)
        else null
    
    override fun saveNextEpisodePrefetchEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_NEXT_EPISODE_PREFETCH)
    }
    
    override fun loadImageCompressionEnabled(): Boolean? =
        if (defaults.objectForKey(KEY_IMAGE_COMPRESSION) != null)
            defaults.boolForKey(KEY_IMAGE_COMPRESSION)
        else null
    
    override fun saveImageCompressionEnabled(enabled: Boolean) {
        defaults.setBool(enabled, KEY_IMAGE_COMPRESSION)
    }
}
