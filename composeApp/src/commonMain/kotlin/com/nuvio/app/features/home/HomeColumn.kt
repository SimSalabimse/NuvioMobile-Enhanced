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

/**
 * Space between Home sections. [com.nuvio.app.core.ui.NuvioScreen] uses the same 12dp list gap.
 * The hero item must not add empty space on top of this.
 */
const val HomeSectionSpacingDp = 12f

/**
 * Where the online column is in the reconnect. The hero slot and the first row are checked
 * in each of these so a full-screen hero cannot open a black band above Continue Watching.
 */
enum class HomeHeroFollowPhase {
    OnlineFromStart,
    OfflineToOnline,
    CoverFading,
    HeroGrowing,
    RowsLoading,
    RowsLoaded,
}

data class HomeHeroFollowState(
    val phase: HomeHeroFollowPhase,
    val viewportHeightDp: Float,
    val heroContentHeightDp: Float,
    /** 0 while the downloads cover owns the screen, 1 when the hero has finished growing. */
    val heroReveal: Float,
    val rowsLoaded: Boolean,
    val sectionSpacingDp: Float = HomeSectionSpacingDp,
)

data class HomeHeroFollowLayout(
    /** Top of the hero. The column adds no screen padding above it. */
    val contentTopDp: Float,
    /** Height of the hero lazy item. */
    val heroSlotHeightDp: Float,
    /** Bottom of the visible artwork. While the slot is still growing this is the clip edge. */
    val heroBottomDp: Float,
    /** Empty band inside the hero item, under the artwork. */
    val spacerBelowHeroDp: Float,
    /** Top of the first row under the hero, including the section gap. */
    val firstRowTopDp: Float,
    /** Artwork bottom to the first row. Section spacing is the whole of this once the spacer is gone. */
    val gapDp: Float,
    /** True when loaded rows start on screen, under the hero. */
    val rowsVisible: Boolean,
)

/**
 * Height of the hero item while it grows in under the downloads cover.
 *
 * The slot tracks the artwork. A viewport-tall slot (`viewportHeightDp * reveal`) is what
 * PR #88 applied: on a phone the portrait artwork is `width * 1.5`, about 30% shorter than
 * the screen, and the leftover was a black band between the carousel and the next row.
 * [viewportHeightDp] stays an input so that formula cannot come back without failing
 * [homeHeroFollowLayout].
 */
fun homeHeroSlotHeightDp(
    viewportHeightDp: Float,
    heroContentHeightDp: Float,
    heroReveal: Float,
): Float {
    if (heroReveal <= 0f || heroContentHeightDp <= 0f || viewportHeightDp.isNaN()) return 0f
    val reveal = if (heroReveal > 1f) 1f else heroReveal
    return heroContentHeightDp * reveal
}

/** Inputs for one [phase] on a phone-shaped viewport. */
fun homeHeroFollowStateFor(
    phase: HomeHeroFollowPhase,
    viewportHeightDp: Float,
    heroContentHeightDp: Float,
    sectionSpacingDp: Float = HomeSectionSpacingDp,
): HomeHeroFollowState {
    val (reveal, rowsLoaded) = when (phase) {
        // Settled online Home, the state in the screenshots.
        HomeHeroFollowPhase.OnlineFromStart -> 1f to true
        // First frame after the cover lets go. The slot has not started growing.
        HomeHeroFollowPhase.OfflineToOnline -> 0f to false
        // Cover is fading and the slot is part way up. Rows are already in the column underneath.
        HomeHeroFollowPhase.CoverFading -> 0.45f to true
        // Late in the same grow, where a viewport-tall target has already passed the artwork.
        HomeHeroFollowPhase.HeroGrowing -> 0.85f to true
        // Online, artwork full size, catalog rows still placeholders.
        HomeHeroFollowPhase.RowsLoading -> 1f to false
        // Online, artwork full size, Continue Watching and the catalogs are in.
        HomeHeroFollowPhase.RowsLoaded -> 1f to true
    }
    return HomeHeroFollowState(
        phase = phase,
        viewportHeightDp = viewportHeightDp,
        heroContentHeightDp = heroContentHeightDp,
        heroReveal = reveal,
        rowsLoaded = rowsLoaded,
        sectionSpacingDp = sectionSpacingDp,
    )
}

/** Where the artwork ends and where the first row starts, for [state]. */
fun homeHeroFollowLayout(state: HomeHeroFollowState): HomeHeroFollowLayout {
    val slot = homeHeroSlotHeightDp(
        viewportHeightDp = state.viewportHeightDp,
        heroContentHeightDp = state.heroContentHeightDp,
        heroReveal = state.heroReveal,
    )
    val contentTop = 0f
    val visibleHero = minOf(state.heroContentHeightDp, slot)
    val heroBottom = contentTop + visibleHero
    val spacer = (slot - visibleHero).coerceAtLeast(0f)
    val firstRowTop = contentTop + slot + state.sectionSpacingDp
    return HomeHeroFollowLayout(
        contentTopDp = contentTop,
        heroSlotHeightDp = slot,
        heroBottomDp = heroBottom,
        spacerBelowHeroDp = spacer,
        firstRowTopDp = firstRowTop,
        gapDp = firstRowTop - heroBottom,
        rowsVisible = state.rowsLoaded && firstRowTop < state.viewportHeightDp,
    )
}
