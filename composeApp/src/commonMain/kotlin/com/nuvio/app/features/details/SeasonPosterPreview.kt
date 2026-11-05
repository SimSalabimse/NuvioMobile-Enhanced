package com.nuvio.app.features.details

internal const val SeasonPosterOverlayAccessibilityId = "season_poster_overlay"

internal const val SeasonPosterDismissDragDp = 72

private const val TmdbImageMarker = "image.tmdb.org/t/p/"

private val TmdbWidthToken = Regex("w\\d+")

private val TmdbHeightToken = Regex("h\\d+")

internal fun seasonPosterAccessibilityId(season: Int): String = "season_poster_$season"

internal data class SeasonPosterFrame(
    val widthPx: Float,
    val heightPx: Float,
)

internal data class SeasonPosterPreviewMotion(
    val durationMillis: Int,
    val zoomsFromAnchor: Boolean,
)

/** Zoom stays under 300 ms. Reduce Motion drops the zoom and cross-fades. */
internal fun seasonPosterPreviewMotion(reduceMotion: Boolean): SeasonPosterPreviewMotion =
    if (reduceMotion) {
        SeasonPosterPreviewMotion(durationMillis = 180, zoomsFromAnchor = false)
    } else {
        SeasonPosterPreviewMotion(durationMillis = 280, zoomsFromAnchor = true)
    }

internal fun seasonPosterPreviewEnabled(url: String?): Boolean = largestSeasonPosterUrl(url) != null

internal fun seasonPosterDismisses(dragPx: Float, thresholdPx: Float): Boolean =
    thresholdPx > 0f && dragPx >= thresholdPx

/**
 * Poster shown full size. TMDB renditions are rewritten to `original`.
 * A blank value means there is nothing to open.
 */
internal fun largestSeasonPosterUrl(url: String?): String? {
    val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = trimmed.lowercase()
    val markerIndex = lower.indexOf(TmdbImageMarker)
    if (markerIndex < 0) return trimmed
    val sizeStart = markerIndex + TmdbImageMarker.length
    val sizeEnd = trimmed.indexOf('/', sizeStart)
    if (sizeEnd <= sizeStart) return trimmed
    val token = lower.substring(sizeStart, sizeEnd)
    if (token == "original") return trimmed
    if (!TmdbWidthToken.matches(token) && !TmdbHeightToken.matches(token)) return trimmed
    return trimmed.substring(0, sizeStart) + "original" + trimmed.substring(sizeEnd)
}

/**
 * Largest rectangle that fits [containerWidthPx] x [containerHeightPx], keeps the image
 * aspect, and does not grow past the bitmap. Unknown bitmap size uses a 2:3 poster.
 */
internal fun fitSeasonPoster(
    containerWidthPx: Float,
    containerHeightPx: Float,
    imageWidthPx: Float,
    imageHeightPx: Float,
): SeasonPosterFrame {
    if (containerWidthPx <= 0f || containerHeightPx <= 0f) return SeasonPosterFrame(0f, 0f)
    val known = imageWidthPx.isFinite() &&
        imageHeightPx.isFinite() &&
        imageWidthPx > 1f &&
        imageHeightPx > 1f
    val aspect = if (known) imageWidthPx / imageHeightPx else 2f / 3f
    if (!aspect.isFinite() || aspect <= 0f) return SeasonPosterFrame(0f, 0f)
    var width = containerWidthPx
    var height = width / aspect
    if (height > containerHeightPx) {
        height = containerHeightPx
        width = height * aspect
    }
    if (known && width > 0f && height > 0f) {
        val scale = minOf(1f, imageWidthPx / width, imageHeightPx / height)
        width *= scale
        height *= scale
    }
    return SeasonPosterFrame(width, height)
}
