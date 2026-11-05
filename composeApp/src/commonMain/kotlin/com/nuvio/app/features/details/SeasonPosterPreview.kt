package com.nuvio.app.features.details

internal const val SeasonPosterOverlayAccessibilityId = "season_poster_overlay"

internal const val SeasonPosterDismissDragDp = 72

/** Second tap on the same poster, close to the first, inside this window opens the preview. */
internal const val SeasonPosterDoubleTapWindowMillis = 300L

/** How far the second tap may land from the first, in dp. */
internal const val SeasonPosterDoubleTapSlopDp = 24

/** Decode the enlarged poster at the screen slot, not the 2000x3000 original. */
internal const val SeasonPosterDecodeMaxWidthPx = 1600

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

/** Memory and disk key for the screen-sized decode, distinct from the list thumbnail. */
internal fun seasonPosterFullImageCacheKey(url: String, widthPx: Int, heightPx: Int): String =
    "season-poster-full:$url@${widthPx}x$heightPx"

/**
 * Pixel size Coil should decode for the enlarged poster. Width is the on-screen slot
 * (already in px), capped so a 3x phone stays sharp without a 2000x3000 bitmap.
 */
internal fun seasonPosterDecodeSizePx(slotWidthPx: Int, slotHeightPx: Int): SeasonPosterFrame {
    if (slotWidthPx <= 0 || slotHeightPx <= 0) return SeasonPosterFrame(0f, 0f)
    val width = slotWidthPx.coerceAtMost(SeasonPosterDecodeMaxWidthPx)
    val height = minOf((width * 3f / 2f).toInt(), slotHeightPx).coerceAtLeast(1)
    return SeasonPosterFrame(width.toFloat(), height.toFloat())
}

/** Hold the season-rail scroll long enough for a second tap to land on the same poster. */
internal fun seasonPosterScrollDelayMillis(defer: Boolean, alreadyPositioned: Boolean): Long =
    if (defer && alreadyPositioned) SeasonPosterDoubleTapWindowMillis else 0L

/** Host and TMDB size segment only. No file name, query, or token. */
internal fun seasonPosterLogTarget(url: String?): String {
    val clean = url?.trim()?.substringBefore('?')?.substringBefore('#')?.takeIf { it.isNotEmpty() }
        ?: return "none"
    val host = clean.substringAfter("://", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('@')
        .takeIf { it.isNotEmpty() }
        ?: return "none"
    val token = tmdbSizeToken(clean)
    return if (token != null) "$host/t/p/$token" else host
}

internal fun seasonPosterPreviewLogLine(
    url: String?,
    bitmapWidthPx: Int,
    bitmapHeightPx: Int,
    listShownMs: Long,
    largeShownMs: Long,
    errorClass: String? = null,
): String {
    val base = "poster=${seasonPosterLogTarget(url)} bitmap=${bitmapWidthPx}x$bitmapHeightPx " +
        "listMs=$listShownMs largeMs=$largeShownMs"
    val error = seasonPosterErrorClass(errorClass)
    return if (error == null) base else "$base error=$error"
}

/** Throwable class name only. Messages, queries, and tokens stay out of the log. */
internal fun seasonPosterErrorClass(name: String?): String? {
    val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val safe = trimmed.all { char -> char.isLetterOrDigit() || char == '_' || char == '$' }
    return if (safe) trimmed else "Error"
}

internal fun seasonPosterDiagnosticsEnabled(
    automationActive: Boolean,
    diagnosticsEnabled: Boolean,
): Boolean = automationActive || diagnosticsEnabled

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
    val season: Int,
)

internal data class SeasonPosterTapDecision(
    val selectSeason: Boolean,
    val enlargePoster: Boolean,
)

/**
 * True when [downTimeMillis] is within [windowMillis] of the previous pointer-up
 * on the same season and near that point. The window is up-to-down, not up-to-up.
 */
internal fun seasonPosterSecondDownOpens(
    previousUp: SeasonPosterTap?,
    season: Int,
    downTimeMillis: Long,
    x: Float,
    y: Float,
    slopPx: Float,
    windowMillis: Long = SeasonPosterDoubleTapWindowMillis,
): Boolean {
    if (previousUp == null || previousUp.season != season) return false
    if (downTimeMillis < previousUp.timeMillis) return false
    if (downTimeMillis - previousUp.timeMillis > windowMillis) return false
    return tapsAreNear(previousUp.x, previousUp.y, x, y, slopPx)
}

private fun tapsAreNear(x0: Float, y0: Float, x1: Float, y1: Float, slopPx: Float): Boolean {
    if (slopPx < 0f) return false
    val dx = x1 - x0
    val dy = y1 - y0
    return dx * dx + dy * dy <= slopPx * slopPx
}

/**
 * Double-tap state for the whole season rail. It lives outside each poster so a
 * selection change can recompose the row without dropping the first pointer-up.
 * A long-press or a drag clears the chain. The preview opens on the second pointer-up,
 * after [onDown] has already accepted the up-to-down window.
 */
internal class SeasonPosterGesture {
    private var lastUp: SeasonPosterTap? = null
    private var pendingEnlarge: Boolean = false
    private var suppressUp: Boolean = false
    private var decision: SeasonPosterTapDecision? = null

    fun onDown(season: Int, timeMillis: Long, x: Float, y: Float, slopPx: Float) {
        pendingEnlarge = seasonPosterSecondDownOpens(
            previousUp = lastUp,
            season = season,
            downTimeMillis = timeMillis,
            x = x,
            y = y,
            slopPx = slopPx,
        )
        suppressUp = false
        decision = null
    }

    fun onUp(season: Int, timeMillis: Long, x: Float, y: Float): SeasonPosterTapDecision? {
        if (suppressUp) {
            suppressUp = false
            pendingEnlarge = false
            return null
        }
        val enlarge = pendingEnlarge
        pendingEnlarge = false
        lastUp = SeasonPosterTap(timeMillis, x, y, season)
        val result = SeasonPosterTapDecision(selectSeason = true, enlargePoster = enlarge)
        decision = result
        return result
    }

    fun onDrag() {
        pendingEnlarge = false
        decision = null
        lastUp = null
    }

    fun onLongPress() {
        pendingEnlarge = false
        decision = null
        lastUp = null
        suppressUp = true
    }

    /** Selection changed the rail. The first pointer-up stays. */
    fun noteSelectionChanged() = Unit

    fun consumeDecision(): SeasonPosterTapDecision? {
        val value = decision
        decision = null
        return value
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
