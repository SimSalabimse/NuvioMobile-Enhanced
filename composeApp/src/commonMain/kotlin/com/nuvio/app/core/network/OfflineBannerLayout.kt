package com.nuvio.app.core.network

/** One text line. The control row below is at least this tall. */
const val OfflineBannerLineDp = 20f
const val OfflineBannerStripPaddingDp = 12f

/**
 * One row, the same band as the back button. Not a second row under it.
 * Large type may grow the row; it stays a single line.
 */
const val OfflineBannerSlimRowDp = 44f

data class OfflineBannerFrame(
    /** Where the label starts, inside the background. Not a gap above the color. */
    val topInsetDp: Float,
    /** Label block. The status inset is not part of this. */
    val stripHeightDp: Float,
    /** Opaque background, from the top edge: inset plus the label block. */
    val totalHeightDp: Float,
)

/** Home and the details screen. Both read [offlineBannerScreenChrome]. */
enum class OfflineBannerScreen {
    Home,
    Details,
}

/** Online draws nothing. Offline and both transitions use the same one-row frame. */
enum class OfflineBannerPhase {
    Online,
    Offline,
    GoingOffline,
    ComingOnline,
    Reconnecting,
}

data class OfflineBannerChrome(
    val drawn: Boolean,
    /** Opaque strip, from the left edge to the right edge. */
    val backgroundWidthDp: Float,
    /** Overlay height: status inset plus one slim row. Not added to the page. */
    val backgroundHeightDp: Float,
    /** Extra page offset. Stays 0 online and offline so the poster does not jump. */
    val contentTopOffsetDp: Float,
    /** The one slim row under the status bar. */
    val contentHeightDp: Float,
)

fun offlineBannerStripHeightDp(fontScale: Float): Float {
    val scale = fontScale.coerceAtLeast(1f)
    val textBlock = OfflineBannerLineDp * scale + OfflineBannerStripPaddingDp
    return maxOf(OfflineBannerSlimRowDp, textBlock)
}

/**
 * The label block is only as tall as its line. [OfflineBannerFrame.totalHeightDp]
 * is the background, and it includes the status inset unless a parent already
 * applied that inset. The banner host does not: it paints this full height from
 * the top edge and places the label at [OfflineBannerFrame.topInsetDp].
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

/**
 * Frame shared by Home and the details screen. The phase does not change the
 * size: both transitions only change alpha and slide. The page offset stays 0.
 */
fun offlineBannerChrome(
    phase: OfflineBannerPhase,
    statusInsetDp: Float,
    fontScale: Float = 1f,
    viewportWidthDp: Float = 390f,
): OfflineBannerChrome = offlineBannerScreenChrome(
    screen = OfflineBannerScreen.Home,
    phase = phase,
    statusInsetDp = statusInsetDp,
    fontScale = fontScale,
    viewportWidthDp = viewportWidthDp,
)

/** Home and details read one scene. The page is not pushed down in either. */
fun offlineBannerScreenChrome(
    screen: OfflineBannerScreen,
    phase: OfflineBannerPhase,
    statusInsetDp: Float,
    fontScale: Float = 1f,
    viewportWidthDp: Float = 390f,
): OfflineBannerChrome {
    val sample = offlinePhaseSample(phase)
    val scene = offlineScene(
        offline = sample.offline,
        progress = sample.progress,
        screen = screen,
        statusInsetDp = statusInsetDp,
        reduceMotion = false,
        fontScale = fontScale,
        viewportWidthDp = viewportWidthDp,
    )
    return OfflineBannerChrome(
        drawn = scene.bannerDrawn,
        backgroundWidthDp = scene.bannerWidthDp,
        backgroundHeightDp = scene.bannerHeightDp,
        contentTopOffsetDp = scene.contentTopOffsetDp,
        contentHeightDp = scene.slimRowDp,
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
