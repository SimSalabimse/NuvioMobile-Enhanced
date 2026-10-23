package com.nuvio.app.core.perf

/**
 * Artwork the app stores or decodes on its own (offline copies, now-playing art) asks TMDB for a
 * rendition no wider than [maxWidthPx] instead of the original file. Other hosts pass through.
 */
internal fun compactArtworkUrl(url: String, maxWidthPx: Int): String {
    if (!PerformanceSettings.isEnabled(PerformanceOptimization.ImageCompression)) return url
    val compact = TmdbImageSizing.forWidth(url, maxWidthPx) ?: return url
    PerformanceMetrics.count(PerformanceCounter.ImagesCompressed)
    return compact
}
