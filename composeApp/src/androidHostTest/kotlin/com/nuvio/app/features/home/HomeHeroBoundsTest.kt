package com.nuvio.app.features.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.components.HomeHeroSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phone portrait. The picture starts with the background. The title stays below the island.
 * 59 is the Pro safe top. 20 is the small-phone top. The card hero still uses that inset once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp")
class HomeHeroBoundsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun artworkIsFullBleedAndTheTitleClearsTheIsland() {
        assertEquals(0f, homeHeroTopPaddingDp(cardStyle = false, contentTopDp = HOME_PRO_PORTRAIT_TOP_DP))
        assertEquals(0f, homeHeroTopPaddingDp(cardStyle = false, contentTopDp = HOME_SMALL_PORTRAIT_TOP_DP))
        assertEquals(HOME_PRO_PORTRAIT_TOP_DP, homeHeroTopPaddingDp(cardStyle = true, contentTopDp = 59f))
        assertEquals(HOME_SMALL_PORTRAIT_TOP_DP, homeHeroTopPaddingDp(cardStyle = true, contentTopDp = 20f))
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().testTag("home-background")) {
                    HomeHeroSection(
                        items = listOf(
                            MetaPreview(
                                id = "fixture-preview-1",
                                type = "movie",
                                name = "Northwind",
                                description = "Synthetic layout title.",
                                releaseInfo = "2024",
                            ),
                        ),
                        viewportHeight = 874.dp,
                        trailerPlaybackEnabled = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        val background = compose.onNodeWithTag("home-background").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        assertEquals(background.top, art.top)
        assertTrue(art.bottom - art.top > HOME_PRO_PORTRAIT_TOP_DP.dp)
        assertTrue(title.top >= art.top + HOME_PRO_PORTRAIT_TOP_DP.dp)
        assertTrue(title.top >= art.top + HOME_SMALL_PORTRAIT_TOP_DP.dp)
        assertTrue(title.top < art.top + (HOME_PRO_PORTRAIT_TOP_DP * 2).dp + (art.bottom - art.top))
    }
}
