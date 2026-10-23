package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.size.Scale
import coil3.size.Size
import coil3.util.DebugLogger
import com.nuvio.app.features.settings.PerformanceSettingsRepository

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    PerformanceSettingsRepository.ensureLoaded()
    
    // Item 2: Poster downsampling + cache cap
    if (PerformanceSettingsRepository.posterDownsamplingEnabled.value) {
        val cacheSizeMB = PerformanceSettingsRepository.posterCacheSizeMB.value
        val cacheSizeBytes = (cacheSizeMB * 1024 * 1024).toLong()
        
        // Configure memory cache with size limit
        memoryCache {
            maxSizeBytes(cacheSizeBytes)
        }
        
        // Configure disk cache with size limit (2x memory cache)
        diskCache {
            maxSizeBytes(cacheSizeBytes * 2)
        }
        
        // Enable downsampling for memory efficiency
        precision(coil3.size.Precision.INEXACT)
    }
    
    return this
}

internal actual val platformProvidesImageLoader: Boolean = false