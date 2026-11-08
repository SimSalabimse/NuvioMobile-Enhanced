package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DetailChromeInsetsTest {
    @Test
    fun immersiveRoutesKeepTheTopEdgeAndOtherPushedScreensDoNot() {
        assertEquals(false, hostRespectsTopSafeArea(null))
        assertEquals(false, hostRespectsTopSafeArea("detail"))
        assertEquals(false, hostRespectsTopSafeArea("stream"))
        assertEquals(false, hostRespectsTopSafeArea("player"))
        assertEquals(true, hostRespectsTopSafeArea("folder"))
        assertEquals(true, hostRespectsTopSafeArea("person"))
        assertEquals(true, hostRespectsTopSafeArea("catalog"))
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
    fun navigationBarIsAddedOnlyWhenItOverlaysTheHero() {
        assertEquals(103f, immersiveTextTopInsetDp(59f, 44f, nativeBarOverlaysHero = true))
        assertEquals(59f, immersiveTextTopInsetDp(59f, 44f, nativeBarOverlaysHero = false))
    }
}
