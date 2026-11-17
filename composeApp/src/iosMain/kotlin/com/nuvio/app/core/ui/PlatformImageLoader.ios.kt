package com.nuvio.app.core.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.analysis.logProcessMemory
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification

/** About 48 MB: under a percent of an 8 GB phone, and enough for the poster rows. */
private const val IosImageMemoryCacheBytes = 48L * 1024 * 1024

private var memoryWarningObserverInstalled = false

internal actual fun ImageLoader.Builder.configurePlatformImageLoader(): ImageLoader.Builder {
    installMemoryWarningObserver()
    return memoryCache {
        MemoryCache.Builder()
            .maxSizeBytes(IosImageMemoryCacheBytes)
            .build()
    }
}

internal actual val platformProvidesImageLoader: Boolean = false

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
private fun installMemoryWarningObserver() {
    if (memoryWarningObserverInstalled) return
    memoryWarningObserverInstalled = true
    NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidReceiveMemoryWarningNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        clearArtworkBackdropCache()
        SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
        InAppLogger.info("ImageLoader", "memory warning: cleared image memory cache")
        logProcessMemory("warning")
        com.nuvio.app.features.diagnostics.BehaviourEvents.record("memory-warning")
        kotlin.native.runtime.GC.collect()
    }
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
fun trimImageMemoryForPlayback() {
    clearArtworkBackdropCache()
    SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear()
    InAppLogger.info("ImageLoader", "cleared image memory cache for playback")
    suspendAnimatedImagesForPlayback()
    kotlin.native.runtime.GC.collect()
    // Phones need a second collect after the cache clear. The cache itself stays at 48 MB.
    kotlin.native.runtime.GC.collect()
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
fun collectAfterPlayback() {
    kotlin.native.runtime.GC.collect()
    resumeAnimatedImagesAfterPlayback()
}
