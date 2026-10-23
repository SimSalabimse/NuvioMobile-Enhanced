package com.nuvio.app.core.perf

/**
 * Rewrites `image.tmdb.org/t/p/<size>/...` URLs to a smaller rendition. Only sizes TMDB serves for
 * every image kind are used, and a URL is never moved to a larger size than it already asks for.
 */
internal object TmdbImageSizing {
    private const val Marker = "image.tmdb.org/t/p/"
    private val widthBuckets = intArrayOf(92, 154, 185, 342, 500, 780, 1280)

    /** The smallest bucket at least [requiredWidthPx] wide, or null when [url] should stay as it is. */
    fun forWidth(url: String, requiredWidthPx: Int): String? {
        if (requiredWidthPx <= 0) return null
        val markerIndex = url.indexOf(Marker)
        if (markerIndex < 0) return null
        val sizeStart = markerIndex + Marker.length
        val sizeEnd = url.indexOf('/', sizeStart)
        if (sizeEnd <= sizeStart) return null
        val currentSize = url.substring(sizeStart, sizeEnd)
        val currentWidth = when {
            currentSize == "original" -> Int.MAX_VALUE
            currentSize.startsWith('w') -> currentSize.drop(1).toIntOrNull() ?: return null
            else -> return null
        }
        val target = widthBuckets.firstOrNull { it >= requiredWidthPx } ?: return null
        if (target >= currentWidth) return null
        return url.substring(0, sizeStart) + "w$target" + url.substring(sizeEnd)
    }
}
