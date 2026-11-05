package com.nuvio.app.features.details

internal const val SeasonPosterOverlayAccessibilityId = "season_poster_overlay"

internal const val SeasonPosterDismissDragDp = 72

/** Second tap on the same poster, close to the first, inside this window opens the preview. */
internal const val SeasonPosterDoubleTapWindowMillis = 300L

/** How far the second tap may land from the first, in dp. */
internal const val SeasonPosterDoubleTapSlopDp = 24

/** Cross-fade from the list poster to the full one. Reduce Motion uses 0 (a swap). */
internal const val SeasonPosterCrossfadeMillis = 180

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

/** Zoom stays under 300 ms. Reduce Motion drops the zoom and fades. */
internal fun seasonPosterPreviewMotion(reduceMotion: Boolean): SeasonPosterPreviewMotion =
    if (reduceMotion) {
        SeasonPosterPreviewMotion(durationMillis = 180, zoomsFromAnchor = false)
    } else {
        SeasonPosterPreviewMotion(durationMillis = 280, zoomsFromAnchor = true)
    }

internal fun seasonPosterCrossfadeMillis(reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else SeasonPosterCrossfadeMillis

internal fun seasonPosterPreviewEnabled(url: String?): Boolean = largestSeasonPosterUrl(url) != null

internal fun seasonPosterDismisses(dragPx: Float, thresholdPx: Float): Boolean =
    thresholdPx > 0f && dragPx >= thresholdPx

/**
 * Poster shown full size. TMDB `/t/p/wNNN/` and `/t/p/hNNN/` renditions are rewritten to
 * `original`. A blank value means there is nothing to open. Other hosts pass through.
 */
internal fun largestSeasonPosterUrl(url: String?): String? {
    val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val token = tmdbSizeToken(trimmed) ?: return trimmed
    if (token == "original") return trimmed
    if (!TmdbWidthToken.matches(token) && !TmdbHeightToken.matches(token)) return trimmed
    return replaceTmdbSize(trimmed, "original")
}

/**
 * Next-smaller TMDB rendition when `original` cannot be loaded: `original` → `w1280` → `w780`.
 * Non-TMDB URLs and sizes already at or below `w780` stay put (null).
 */
internal fun seasonPosterFallbackUrl(url: String?): String? {
    val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val token = tmdbSizeToken(trimmed) ?: return null
    val replacement = when (token) {
        "original" -> "w1280"
        "w1280" -> "w780"
        else -> return null
    }
    return replaceTmdbSize(trimmed, replacement)
}

/** Memory and disk key for the full-size decode, distinct from the list thumbnail. */
internal fun seasonPosterFullImageCacheKey(url: String): String = "season-poster-full:$url"

private fun tmdbSizeToken(url: String): String? {
    val lower = url.lowercase()
    val markerIndex = lower.indexOf(TmdbImageMarker)
    if (markerIndex < 0) return null
    val sizeStart = markerIndex + TmdbImageMarker.length
    val sizeEnd = url.indexOf('/', sizeStart)
    if (sizeEnd <= sizeStart) return null
    return lower.substring(sizeStart, sizeEnd)
}

private fun replaceTmdbSize(url: String, replacement: String): String {
    val lower = url.lowercase()
    val markerIndex = lower.indexOf(TmdbImageMarker)
    val sizeStart = markerIndex + TmdbImageMarker.length
    val sizeEnd = url.indexOf('/', sizeStart)
    return url.substring(0, sizeStart) + replacement + url.substring(sizeEnd)
}

internal data class SeasonPosterTap(
    val timeMillis: Long,
    val x: Float,
    val y: Float,
)

internal data class SeasonPosterTapDecision(
    val selectSeason: Boolean,
    val enlargePoster: Boolean,
)

/**
 * Every tap selects the season. A second tap within [windowMillis] and [slopPx] of the
 * previous one also opens the preview. The caller skips this for a long-press.
 */
internal fun seasonPosterTapDecision(
    previous: SeasonPosterTap?,
    timeMillis: Long,
    x: Float,
    y: Float,
    slopPx: Float,
    windowMillis: Long = SeasonPosterDoubleTapWindowMillis,
): SeasonPosterTapDecision {
    val enlarge = previous != null &&
        timeMillis >= previous.timeMillis &&
        timeMillis - previous.timeMillis <= windowMillis &&
        tapsAreNear(previous.x, previous.y, x, y, slopPx)
    return SeasonPosterTapDecision(selectSeason = true, enlargePoster = enlarge)
}

private fun tapsAreNear(x0: Float, y0: Float, x1: Float, y1: Float, slopPx: Float): Boolean {
    if (slopPx < 0f) return false
    val dx = x1 - x0
    val dy = y1 - y0
    return dx * dx + dy * dy <= slopPx * slopPx
}

internal class SeasonPosterTapTracker {
    private var previous: SeasonPosterTap? = null

    fun onTap(timeMillis: Long, x: Float, y: Float, slopPx: Float): SeasonPosterTapDecision {
        val decision = seasonPosterTapDecision(previous, timeMillis, x, y, slopPx = slopPx)
        previous = SeasonPosterTap(timeMillis, x, y)
        return decision
    }

    fun onLongPress() {
        previous = null
    }
}

/**
 * Arms a tap while the finger stays within the touch slop. A drag or [cancel] (long-press)
 * disarms it so the click handler does not treat that gesture as a tap.
 */
internal class SeasonPosterPointerState {
    private var tracking: Boolean = false
    private var armed: Boolean = false
    private var originX: Float = 0f
    private var originY: Float = 0f
    private var x: Float = 0f
    private var y: Float = 0f

    fun down(x: Float, y: Float) {
        tracking = true
        armed = true
        originX = x
        originY = y
        this.x = x
        this.y = y
    }

    fun move(x: Float, y: Float, slopPx: Float) {
        if (!tracking) return
        val dx = x - originX
        val dy = y - originY
        if (dx * dx + dy * dy > slopPx * slopPx) {
            tracking = false
            armed = false
        } else {
            this.x = x
            this.y = y
        }
    }

    fun up() {
        if (!tracking) armed = false
        tracking = false
    }

    fun cancel() {
        tracking = false
        armed = false
    }

    fun consumeTap(): Pair<Float, Float>? {
        if (!armed) return null
        armed = false
        return x to y
    }
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
