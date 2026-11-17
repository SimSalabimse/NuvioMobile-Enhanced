package com.nuvio.app.features.offline

/**
 * Actions the offline screens already expose. A fix may not drop one of these.
 */
enum class OfflineReachableAction {
    ShowHomeAnyway,
    OpenDownloads,
    PlayDownloadedTitle,
    TitleLongPress,
    ResumeLongPress,
    BannerRecheck,
}

fun reachableOfflineActions(
    onDownloadsHome: Boolean,
    bannerVisible: Boolean,
): Set<OfflineReachableAction> = buildSet {
    if (onDownloadsHome) {
        add(OfflineReachableAction.ShowHomeAnyway)
        add(OfflineReachableAction.OpenDownloads)
        add(OfflineReachableAction.PlayDownloadedTitle)
        add(OfflineReachableAction.TitleLongPress)
        add(OfflineReachableAction.ResumeLongPress)
    }
    if (bannerVisible) add(OfflineReachableAction.BannerRecheck)
}
