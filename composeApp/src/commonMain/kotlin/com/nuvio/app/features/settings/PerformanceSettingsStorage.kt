package com.nuvio.app.features.settings

/**
 * Mobile performance optimization settings storage.
 * Each optimization has a toggle switch so impact can be measured independently.
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance analysis.
 */
internal interface PerformanceSettingsStorage {
    fun loadSymbolStrippingEnabled(): Boolean?
    fun saveSymbolStrippingEnabled(enabled: Boolean)
    
    fun loadPosterDownsamplingEnabled(): Boolean?
    fun savePosterDownsamplingEnabled(enabled: Boolean)
    
    fun loadPosterCacheSizeMB(): Int?
    fun savePosterCacheSizeMB(sizeMB: Int)
    
    fun loadParallelStartupEnabled(): Boolean?
    fun saveParallelStartupEnabled(enabled: Boolean)
    
    fun loadCachedHomeFirstEnabled(): Boolean?
    fun saveCachedHomeFirstEnabled(enabled: Boolean)
    
    fun loadRequestDedupeEnabled(): Boolean?
    fun saveRequestDedupeEnabled(enabled: Boolean)
    
    fun loadShortHttpCachesEnabled(): Boolean?
    fun saveShortHttpCachesEnabled(enabled: Boolean)
    
    fun loadHttpCacheTTLSeconds(): Int?
    fun saveHttpCacheTTLSeconds(ttlSeconds: Int)
    
    fun loadCappedLruCachesEnabled(): Boolean?
    fun saveCappedLruCachesEnabled(enabled: Boolean)
    
    fun loadPausePrefetchDuringPlaybackEnabled(): Boolean?
    fun savePausePrefetchDuringPlaybackEnabled(enabled: Boolean)
    
    fun loadNextEpisodePrefetchEnabled(): Boolean?
    fun saveNextEpisodePrefetchEnabled(enabled: Boolean)
    
    fun loadImageCompressionEnabled(): Boolean?
    fun saveImageCompressionEnabled(enabled: Boolean)
}

internal expect object PerformanceSettingsStoragePlatform : PerformanceSettingsStorage
