package com.nuvio.app.core.perf

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Scale
import coil3.size.pxOrElse

private const val PosterMemoryCacheCapBytes = 96L * 1024L * 1024L

/** Asks TMDB for the smallest rendition that still covers the slot Coil is decoding for. */
internal class PosterDownsampleInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (!PerformanceSettings.isEnabled(PerformanceOptimization.PosterDownsampling)) return chain.proceed()
        val url = chain.request.data as? String ?: return chain.proceed()
        val width = chain.size.width.pxOrElse { -1 }
        if (width <= 0) return chain.proceed()
        val height = chain.size.height.pxOrElse { -1 }
        // A cropped slot may show a landscape source scaled to its height; cover up to 16:9.
        val requiredWidth = if (chain.request.scale == Scale.FILL && height > 0) maxOf(width, height * 16 / 9) else width
        val smaller = TmdbImageSizing.forWidth(url, requiredWidth) ?: return chain.proceed()
        PerformanceMetrics.count(PerformanceCounter.PostersDownsampled)
        return chain.withRequest(chain.request.newBuilder().data(smaller).build()).proceed()
    }
}

/** Interceptors every app image loader adds after its own (offline artwork, custom poster fallback). */
internal fun ComponentRegistry.Builder.addPerformanceInterceptors() {
    add(PosterDownsampleInterceptor())
}

/** Caps the decoded-image memory cache while poster downsampling is on; restores Coil's default when off. */
internal fun ImageLoader.applyPosterMemoryCachePolicy(enabled: Boolean) {
    val cache = memoryCache ?: return
    cache.maxSize = if (enabled) minOf(cache.initialMaxSize, PosterMemoryCacheCapBytes) else cache.initialMaxSize
}
