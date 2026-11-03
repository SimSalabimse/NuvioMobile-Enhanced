package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineBannerChromeTest {
    @Test
    fun backgroundCoversTheStatusInsetOnHomeAndDetails() {
        val screens = OfflineBannerScreen.entries
        val phases = OfflineBannerPhase.entries
        val insets = listOf(0f, 47f, 59f)
        assertEquals(listOf(OfflineBannerScreen.Home, OfflineBannerScreen.Details), screens)
        assertEquals(
            listOf(
                OfflineBannerPhase.Online,
                OfflineBannerPhase.Offline,
                OfflineBannerPhase.GoingOffline,
                OfflineBannerPhase.ComingOnline,
                OfflineBannerPhase.Reconnecting,
            ),
            phases,
        )
        val contentHeight = offlineBannerStripHeightDp(fontScale = 1f)

        for (screen in screens) {
            for (phase in phases) {
                for (inset in insets) {
                    val chrome = offlineBannerScreenChrome(
                        screen = screen,
                        phase = phase,
                        statusInsetDp = inset,
                    )
                    val shared = offlineBannerChrome(
                        phase = phase,
                        statusInsetDp = inset,
                    )
                    assertEquals(shared, chrome, "$screen $phase inset=$inset")
                    assertEquals(contentHeight, chrome.contentHeightDp, "$screen $phase inset=$inset")
                    assertEquals(
                        inset + chrome.contentHeightDp,
                        chrome.backgroundHeightDp,
                        "$screen $phase inset=$inset",
                    )
                    assertEquals(0f, chrome.contentTopOffsetDp, "$screen $phase inset=$inset")
                    assertEquals(phase != OfflineBannerPhase.Online, chrome.drawn, "$screen $phase")
                }
            }
        }

        val offline = offlineBannerChrome(OfflineBannerPhase.Offline, statusInsetDp = 59f)
        val fading = offlineBannerChrome(OfflineBannerPhase.Reconnecting, statusInsetDp = 59f)
        assertEquals(offline.backgroundHeightDp, fading.backgroundHeightDp)
        assertEquals(offline.contentTopOffsetDp, fading.contentTopOffsetDp)
        assertTrue(fading.drawn)
        assertFalse(offlineBannerChrome(OfflineBannerPhase.Online, statusInsetDp = 59f).drawn)
        assertEquals(250, OfflineBannerHideFadeMs)
    }
}
