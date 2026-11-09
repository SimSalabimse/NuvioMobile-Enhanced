package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeInsetsTest {
    @Test
    fun portraitReturnUsesTheWindowTopWhenTheStatusBarIsStillZero() {
        assertEquals(HOME_PRO_PORTRAIT_TOP_DP, homeContentTopInsetDp(statusBarDp = 0f, windowTopDp = HOME_PRO_PORTRAIT_TOP_DP))
        assertEquals(HOME_SMALL_PORTRAIT_TOP_DP, homeContentTopInsetDp(0f, HOME_SMALL_PORTRAIT_TOP_DP))
    }

    @Test
    fun theSameInsetFromBothSourcesIsAppliedOnce() {
        val once = homeContentTopInsetDp(HOME_PRO_PORTRAIT_TOP_DP, HOME_PRO_PORTRAIT_TOP_DP)
        assertEquals(HOME_PRO_PORTRAIT_TOP_DP, once)
        assertTrue(once < HOME_PRO_PORTRAIT_TOP_DP * 2)
        assertEquals(0f, homeContentTopInsetDp(-4f, -8f))
    }

    @Test
    fun theTitleMovesOnlyWhenThePictureEndsInsideTheUnsafeBand() {
        assertEquals(0f, homeInteractiveClearanceDp(artworkBottomDp = 226f, safeTopDp = 59f))
        assertEquals(174f, homeInteractiveClearanceDp(artworkBottomDp = 226f, safeTopDp = 400f))
        assertEquals(0f, homeInteractiveClearanceDp(artworkBottomDp = 226f, safeTopDp = 0f))
        assertEquals(0f, homeInteractiveClearanceDp(artworkBottomDp = 226f, safeTopDp = -8f))
    }

    @Test
    fun theTopEdgeIsAQuietFadeUnlessContrastAsksForASolidBand() {
        assertEquals(HomeTopEdgeKind.None, homeTopEdgeKind(0f, cardStyle = false, reduceTransparency = false, increaseContrast = false))
        assertEquals(HomeTopEdgeKind.None, homeTopEdgeKind(59f, cardStyle = true, reduceTransparency = false, increaseContrast = false))
        assertEquals(HomeTopEdgeKind.QuietFade, homeTopEdgeKind(59f, cardStyle = false, reduceTransparency = false, increaseContrast = false))
        assertEquals(HomeTopEdgeKind.Solid, homeTopEdgeKind(59f, cardStyle = false, reduceTransparency = true, increaseContrast = false))
        assertEquals(HomeTopEdgeKind.Solid, homeTopEdgeKind(59f, cardStyle = false, reduceTransparency = false, increaseContrast = true))
    }

    @Test
    fun theQuietFadeExtendsPastTheInsetAndStopsAtTheTitle() {
        assertEquals(87f, homeTopEdgeHeightDp(safeTopDp = 59f, artworkBottomDp = 226f, solid = false))
        assertEquals(400f, homeTopEdgeHeightDp(safeTopDp = 400f, artworkBottomDp = 226f, solid = false))
        assertEquals(80f, homeTopEdgeHeightDp(safeTopDp = 80f, artworkBottomDp = 226f, solid = true))
        assertEquals(0f, homeTopEdgeHeightDp(safeTopDp = 0f, artworkBottomDp = 226f, solid = false))
    }
}
