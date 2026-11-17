package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailChromeInsetsTest {
    @Test
    fun detailArtworkRunsUnderTheStatusAreaAndTheHeaderUsesTheInsetOnce() {
        assertEquals(false, hostRespectsTopSafeArea("detail"))
        assertEquals(0f, detailHeroImageTopDp("detail"))
        assertEquals(59f, detailChromeTopInsetDp(59f, 59f, hostRespectsTop = hostRespectsTopSafeArea("detail")))
        assertEquals(false, hostRespectsTopSafeArea("stream"))
        assertEquals(false, hostRespectsTopSafeArea("player"))
        assertEquals(true, hostRespectsTopSafeArea("folder"))
        assertEquals(true, hostRespectsTopSafeArea("person"))
        assertEquals(true, hostRespectsTopSafeArea("catalog"))
    }

    @Test
    fun theQuietFadeCoversTheInsetAndStopsBeforeTheTitle() {
        assertEquals(87f, scrollEdgeHeightDp(safeTopDp = 59f, contentTopDp = 226f, solid = false))
        assertEquals(400f, scrollEdgeHeightDp(safeTopDp = 400f, contentTopDp = 226f, solid = false))
        assertEquals(80f, scrollEdgeHeightDp(safeTopDp = 80f, contentTopDp = 226f, solid = true))
        assertEquals(0f, scrollEdgeHeightDp(safeTopDp = 0f, contentTopDp = 226f, solid = false))
        assertTrue(scrollEdgeHeightDp(59f, 226f, solid = false) > 59f)
        assertEquals(ScrollEdgeKind.QuietFade, scrollEdgeKind(59f, suppressed = false, reduceTransparency = false, increaseContrast = false))
        assertEquals(ScrollEdgeKind.Solid, scrollEdgeKind(59f, suppressed = false, reduceTransparency = true, increaseContrast = false))
        assertEquals(ScrollEdgeKind.None, scrollEdgeKind(0f, suppressed = false, reduceTransparency = false, increaseContrast = false))
    }

    @Test
    fun hostThatAlreadyRespectsTheTopAddsNoSecondInset() {
        assertEquals(0f, detailChromeTopInsetDp(59f, 59f, hostRespectsTop = true))
        assertEquals(59f, detailChromeTopInsetDp(59f, 0f, hostRespectsTop = false))
        assertEquals(59f, detailChromeTopInsetDp(0f, 59f, hostRespectsTop = false))
    }

    @Test
    fun shortArtworkMovesTheTitleBelowTheIslandAndTallArtworkDoesNot() {
        assertEquals(8f, headingTopPaddingDp(artworkHeightDp = 220f, topInsetDp = 59f))
        assertEquals(59f, headingTopPaddingDp(artworkHeightDp = 0f, topInsetDp = 59f))
        assertEquals(19f, headingTopPaddingDp(artworkHeightDp = 40f, topInsetDp = 59f))
    }

    @Test
    fun heroBackButtonIsAbsentWhenTheNativeBackIsShowing() {
        assertEquals(false, detailHeroBackShown(nativeBackVisible = true))
        assertEquals(true, detailHeroBackShown(nativeBackVisible = false))
    }

    @Test
    fun heroBackPaddingDoesNotAddTheStatusBarWhenTheHostAlreadyDid() {
        assertEquals(8f, detailHeroBackTopDp(hostRespectsTop = true, statusBarDp = 59f))
        assertEquals(67f, detailHeroBackTopDp(hostRespectsTop = false, statusBarDp = 59f))
    }

    @Test
    fun navigationBarIsAddedOnlyWhenItOverlaysTheHero() {
        assertEquals(103f, immersiveTextTopInsetDp(59f, 44f, nativeBarOverlaysHero = true))
        assertEquals(59f, immersiveTextTopInsetDp(59f, 44f, nativeBarOverlaysHero = false))
    }
}
