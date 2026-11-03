package com.nuvio.app.features.home

import com.nuvio.app.core.network.OfflineTransitionDurationMs
import com.nuvio.app.core.network.offlineTransitionMotion

/** Same clock as the offline banner. */
const val HomeReconnectMotionMs = OfflineTransitionDurationMs

data class HomeReconnectMotion(
    val durationMs: Int,
    val snap: Boolean,
)

fun homeReconnectMotion(reduceMotion: Boolean): HomeReconnectMotion {
    val motion = offlineTransitionMotion(reduceMotion)
    return HomeReconnectMotion(durationMs = motion.durationMs, snap = motion.snap)
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

/**
 * Reduce Motion snaps. Going offline used to snap as well, which hid the fade.
 * Both directions now use [offlineTransitionMotion]. The flag stays so callers
 * can still say which way the cover is moving.
 */
fun homeCoverSnapsOn(goingOffline: Boolean, reduceMotion: Boolean): Boolean {
    if (goingOffline && reduceMotion) return true
    return offlineTransitionMotion(reduceMotion).snap
}
