package com.nuvio.app.core.network

/** One text line plus the padding around it. The status inset is not part of this strip. */
const val OfflineBannerLineDp = 20f
const val OfflineBannerStripPaddingDp = 12f

data class OfflineBannerFrame(
    val topInsetDp: Float,
    val stripHeightDp: Float,
    val totalHeightDp: Float,
)

fun offlineBannerStripHeightDp(fontScale: Float): Float {
    val scale = fontScale.coerceAtLeast(1f)
    return OfflineBannerLineDp * scale + OfflineBannerStripPaddingDp
}

/**
 * The colored strip is only as tall as its line. The status inset sits above that
 * strip, and it is omitted when the parent already applied it.
 */
fun offlineBannerFrame(
    statusInsetDp: Float,
    statusInsetAlreadyApplied: Boolean,
    fontScale: Float = 1f,
): OfflineBannerFrame {
    val top = if (statusInsetAlreadyApplied) 0f else statusInsetDp.coerceAtLeast(0f)
    val strip = offlineBannerStripHeightDp(fontScale)
    return OfflineBannerFrame(
        topInsetDp = top,
        stripHeightDp = strip,
        totalHeightDp = top + strip,
    )
}

/** Content starts below the banner, with a small gap so the actions are not flush against it. */
fun offlineDownloadsHomeTopPaddingDp(statusInsetDp: Float, fontScale: Float): Float =
    offlineBannerFrame(
        statusInsetDp = statusInsetDp,
        statusInsetAlreadyApplied = false,
        fontScale = fontScale,
    ).totalHeightDp + 8f

/**
 * Floor for the offline home actions. The control itself grows with its text;
 * this floor keeps a 48dp target at the default size and three lines at large type.
 */
fun offlineHomeActionMinHeightDp(fontScale: Float): Float {
    val scale = fontScale.coerceIn(1f, 3.5f)
    val lines = if (scale > 1.3f) 3f else 1f
    // 52 matches the primary button's previous fixed height, so the row does not shrink.
    return maxOf(52f, OfflineBannerLineDp * scale * lines + 16f)
}

fun offlineHomeActionsClearBanner(
    statusInsetDp: Float,
    fontScale: Float,
): Boolean {
    val bannerBottom = offlineBannerFrame(
        statusInsetDp = statusInsetDp,
        statusInsetAlreadyApplied = false,
        fontScale = fontScale,
    ).totalHeightDp
    val contentTop = offlineDownloadsHomeTopPaddingDp(statusInsetDp, fontScale)
    return contentTop >= bannerBottom &&
        offlineHomeActionMinHeightDp(fontScale) >= 48f
}
