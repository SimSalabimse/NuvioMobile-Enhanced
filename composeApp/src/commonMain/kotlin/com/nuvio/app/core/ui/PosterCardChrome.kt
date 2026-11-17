package com.nuvio.app.core.ui

/**
 * Visibility of every poster overlay and action on [NuvioPosterCard].
 * The resume bar is additive. The other flags are the overlays and actions that
 * already existed and must stay reachable.
 */
internal data class PosterCardChrome(
    val showBottomScrim: Boolean,
    val showBottomLeftLogo: Boolean,
    val showBottomLeftText: Boolean,
    val showWatchedBadge: Boolean,
    val showStatusLabel: Boolean,
    val clickEnabled: Boolean,
    val longClickEnabled: Boolean,
    val showTitle: Boolean,
    val showDetailLine: Boolean,
    val showResumeBar: Boolean,
)

internal val ExistingPosterOverlayActions: List<String> = listOf(
    "bottomLeftLogo",
    "bottomLeftText",
    "watchedBadge",
    "statusLabel",
    "click",
    "longClick",
    "title",
    "detailLine",
)

internal fun posterCardChrome(
    bottomLeftLogoUrl: String?,
    bottomLeftText: String?,
    isWatched: Boolean,
    statusLabel: String?,
    hasClick: Boolean,
    hasLongClick: Boolean,
    showTitleBelow: Boolean,
    hideLabels: Boolean,
    detailLine: String?,
    resumeFraction: Float?,
): PosterCardChrome {
    val showLogo = !bottomLeftLogoUrl.isNullOrBlank()
    val showText = !showLogo && !bottomLeftText.isNullOrBlank()
    val titleVisible = showTitleBelow && !hideLabels
    return PosterCardChrome(
        showBottomScrim = showLogo || showText,
        showBottomLeftLogo = showLogo,
        showBottomLeftText = showText,
        showWatchedBadge = isWatched,
        showStatusLabel = statusLabel != null,
        clickEnabled = hasClick,
        longClickEnabled = hasLongClick,
        showTitle = titleVisible,
        showDetailLine = titleVisible && !detailLine.isNullOrBlank(),
        showResumeBar = resumeFraction != null,
    )
}

internal fun PosterCardChrome.reachableExistingActions(): List<String> = buildList {
    if (showBottomLeftLogo) add("bottomLeftLogo")
    if (showBottomLeftText) add("bottomLeftText")
    if (showWatchedBadge) add("watchedBadge")
    if (showStatusLabel) add("statusLabel")
    if (clickEnabled) add("click")
    if (longClickEnabled) add("longClick")
    if (showTitle) add("title")
    if (showDetailLine) add("detailLine")
}
