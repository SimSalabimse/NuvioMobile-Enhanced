package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PosterCardChromeTest {
    @Test
    fun existingPosterOverlaysAndActionsStayReachable() {
        val withLogoAndResume = posterCardChrome(
            bottomLeftLogoUrl = "https://example.com/logo.png",
            bottomLeftText = "Fallback title",
            isWatched = true,
            statusLabel = "In library",
            hasClick = true,
            hasLongClick = true,
            showTitleBelow = true,
            hideLabels = false,
            detailLine = "2014",
            resumeFraction = 0.5f,
        )
        val withBottomText = posterCardChrome(
            bottomLeftLogoUrl = null,
            bottomLeftText = "Fallback title",
            isWatched = true,
            statusLabel = "In library",
            hasClick = true,
            hasLongClick = true,
            showTitleBelow = true,
            hideLabels = false,
            detailLine = "2014",
            resumeFraction = null,
        )

        val reached = (withLogoAndResume.reachableExistingActions() + withBottomText.reachableExistingActions()).toSet()
        assertEquals(ExistingPosterOverlayActions.toSet(), reached)

        assertTrue(withLogoAndResume.showResumeBar)
        assertTrue(withLogoAndResume.showWatchedBadge)
        assertTrue(withLogoAndResume.showStatusLabel)
        assertTrue(withLogoAndResume.showBottomLeftLogo)
        assertTrue(withLogoAndResume.clickEnabled)
        assertTrue(withLogoAndResume.longClickEnabled)
        assertTrue(withLogoAndResume.showTitle)
        assertTrue(withLogoAndResume.showDetailLine)

        assertFalse(withBottomText.showResumeBar)
        assertTrue(withBottomText.showWatchedBadge)
        assertTrue(withBottomText.showStatusLabel)
        assertTrue(withBottomText.showBottomLeftText)
        assertTrue(withBottomText.clickEnabled)
        assertTrue(withBottomText.longClickEnabled)
    }
}
