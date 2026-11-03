package com.nuvio.app.core.network

/** One text line plus the padding around it. The status inset is not part of this strip. */
const val OfflineBannerLineDp = 20f
const val OfflineBannerStripPaddingDp = 12f

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

/** Online draws nothing. Offline and the reconnect fade use the same frame. */
enum class OfflineBannerPhase {
    Online,
    Offline,
    Reconnecting,
}

data class OfflineBannerChrome(
    val drawn: Boolean,
    val backgroundHeightDp: Float,
    val contentTopOffsetDp: Float,
    val contentHeightDp: Float,
)

fun offlineBannerStripHeightDp(fontScale: Float): Float {
    val scale = fontScale.coerceAtLeast(1f)
    return OfflineBannerLineDp * scale + OfflineBannerStripPaddingDp
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
 * size: the reconnect fade only changes alpha. Online is not drawn.
 */
fun offlineBannerChrome(
    phase: OfflineBannerPhase,
    statusInsetDp: Float,
    fontScale: Float = 1f,
): OfflineBannerChrome {
    val frame = offlineBannerFrame(
        statusInsetDp = statusInsetDp,
        statusInsetAlreadyApplied = false,
        fontScale = fontScale,
    )
    return OfflineBannerChrome(
        drawn = phase != OfflineBannerPhase.Online,
        backgroundHeightDp = frame.totalHeightDp,
        contentTopOffsetDp = frame.topInsetDp,
        contentHeightDp = frame.stripHeightDp,
    )
}

/** Home and details have no separate inset math. */
fun offlineBannerScreenChrome(
    screen: OfflineBannerScreen,
    phase: OfflineBannerPhase,
    statusInsetDp: Float,
    fontScale: Float = 1f,
): OfflineBannerChrome {
    val shared = offlineBannerChrome(
        phase = phase,
        statusInsetDp = statusInsetDp,
        fontScale = fontScale,
    )
    return when (screen) {
        OfflineBannerScreen.Home,
        OfflineBannerScreen.Details,
        -> shared
    }
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
