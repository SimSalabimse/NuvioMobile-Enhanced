package com.nuvio.app.features.home

/** How long the offline home cover fades and the hero slot grows. */
const val HomeReconnectMotionMs = 250

data class HomeReconnectMotion(
    val durationMs: Int,
    val snap: Boolean,
)

fun homeReconnectMotion(reduceMotion: Boolean): HomeReconnectMotion =
    if (reduceMotion) {
        HomeReconnectMotion(durationMs = 0, snap = true)
    } else {
        HomeReconnectMotion(durationMs = HomeReconnectMotionMs, snap = false)
    }

/**
 * Sections of Home in each mode. Offline-only rows are faded out by the cover.
 * They are not deleted from the offline mode, and the online mode never listed them.
 */
enum class HomeColumnSection {
    OpenDownloads,
    ShowHomeAnyway,
    OfflineContinueWatching,
    Downloaded,
    Hero,
    OnlineContinueWatching,
    CatalogRows,
}

fun homeColumnSections(offlineDownloadsHome: Boolean): List<HomeColumnSection> =
    if (offlineDownloadsHome) {
        listOf(
            HomeColumnSection.OpenDownloads,
            HomeColumnSection.ShowHomeAnyway,
            HomeColumnSection.OfflineContinueWatching,
            HomeColumnSection.Downloaded,
        )
    } else {
        listOf(
            HomeColumnSection.Hero,
            HomeColumnSection.OnlineContinueWatching,
            HomeColumnSection.CatalogRows,
        )
    }

/** Offline-only rows leave by fading. The hero slot grows under them so later rows slide. */
fun homeSectionFadesOutWhenLeavingOffline(section: HomeColumnSection): Boolean =
    section == HomeColumnSection.OpenDownloads ||
        section == HomeColumnSection.ShowHomeAnyway ||
        section == HomeColumnSection.OfflineContinueWatching ||
        section == HomeColumnSection.Downloaded

/** Going offline shows the downloads cover on this frame. Leaving it fades unless Reduce Motion is on. */
fun homeCoverSnapsOn(goingOffline: Boolean, reduceMotion: Boolean): Boolean =
    goingOffline || reduceMotion
