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
        assertEquals(0f, homeHeroTopPaddingDp(cardStyle = false, contentTopDp = HOME_PRO_PORTRAIT_TOP_DP))
        assertEquals(0f, homeHeroTopPaddingDp(cardStyle = true, contentTopDp = HOME_SMALL_PORTRAIT_TOP_DP))
    }
}
