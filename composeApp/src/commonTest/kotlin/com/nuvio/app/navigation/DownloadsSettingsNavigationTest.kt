package com.nuvio.app.navigation

import com.nuvio.app.downloadsSettingsPushRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadsSettingsNavigationTest {
    @Test
    fun nativeSettingsPushStaysOnTheLibraryStackAboveDownloads() {
        val downloads = DownloadsRoute("Downloads")
        val settings = downloadsSettingsPushRoute(
            useNativeNavigation = true,
            title = downloads.title,
        )

        assertEquals(DownloadsSettingsRoute("Downloads"), settings)
        // Library, not Settings. A settings-tab route would leave the downloads stack.
        assertEquals("Library", settings?.preferredTabName)
        assertTrue(settings!!.navigationIdentity != downloads.navigationIdentity)
    }

    @Test
    fun embeddedSettingsStayOnTheDownloadsScreen() {
        assertNull(
            downloadsSettingsPushRoute(
                useNativeNavigation = false,
                title = "Downloads",
            ),
        )
    }
}
