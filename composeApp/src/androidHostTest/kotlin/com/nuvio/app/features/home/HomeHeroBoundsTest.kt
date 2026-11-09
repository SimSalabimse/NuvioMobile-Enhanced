package com.nuvio.app.features.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.components.HomeEdgeFlags
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.LocalHomeContentTopInset
import com.nuvio.app.features.home.components.LocalHomeEdgeFlags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phone portrait. The picture starts with the background.
 * A title under a tall picture is not proof it cleared the safe area: the picture is taller than 59.
 * The live inset is supplied to the same reader the hero uses.
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

    @Test
    fun theTitleClearsASafeAreaTallerThanThePictureAndTheTopEdgeDoesNotCoverIt() {
        val safeTop = 400.dp
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalHomeContentTopInset provides safeTop) {
                    Box(Modifier.fillMaxSize().testTag("home-background")) {
                        hero()
                    }
                }
            }
        }
        compose.waitForIdle()
        val background = compose.onNodeWithTag("home-background").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val edge = compose.onNodeWithTag(HOME_HERO_TOP_EDGE_TAG).getBoundsInRoot()
        assertEquals(background.top, art.top)
        assertTrue(art.bottom - art.top >= 360.dp)
        assertTrue(title.top >= safeTop)
        assertTrue(title.top < art.bottom)
        assertEquals(art.top, edge.top)
        assertTrue(edge.bottom <= title.top)
        assertTrue(edge.bottom - edge.top >= safeTop)
    }

    @Test
    fun aZeroInsetDoesNotAddABandOrMoveTheTitle() {
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalHomeContentTopInset provides 0.dp) {
                    Box(Modifier.fillMaxSize().testTag("home-background")) {
                        hero()
                    }
                }
            }
        }
        compose.waitForIdle()
        val background = compose.onNodeWithTag("home-background").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        compose.onNodeWithTag(HOME_HERO_TOP_EDGE_TAG).assertDoesNotExist()
        assertEquals(background.top, art.top)
        assertTrue(art.bottom - art.top >= 360.dp)
        assertTrue(title.top > art.top)
        assertTrue(title.bottom <= art.bottom + 1.dp)
    }

    @Test
    fun theQuietFadeCoversTheInsetAndEasesPastItWithoutCoveringTheTitle() {
        val safeTop = HOME_PRO_PORTRAIT_TOP_DP.dp
        var clicks = 0
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalHomeContentTopInset provides safeTop) {
                    Box(Modifier.fillMaxSize().testTag("home-background")) {
                        hero(onItemClick = { clicks += 1 })
                    }
                }
            }
        }
        compose.waitForIdle()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val edge = compose.onNodeWithTag(HOME_HERO_TOP_EDGE_TAG).getBoundsInRoot()
        assertEquals(0.dp, art.top)
        assertTrue(art.bottom - art.top >= 360.dp)
        assertTrue(art.bottom - art.top > 402.dp * 9f / 16f)
        assertTrue(edge.bottom - edge.top > safeTop)
        assertTrue(edge.bottom - edge.top <= safeTop + 28.dp)
        assertTrue(edge.bottom < title.top)
        assertTrue(title.top < art.bottom)
        compose.onNodeWithTag("home-background").performTouchInput {
            click(Offset(width / 2f, 8f))
        }
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun increaseContrastUsesASolidEdgeThatStillStopsAtTheTitle() {
        val safeTop = 80.dp
        var clicks = 0
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(
                    LocalHomeContentTopInset provides safeTop,
                    LocalHomeEdgeFlags provides HomeEdgeFlags(increaseContrast = true),
                ) {
                    Box(Modifier.fillMaxSize().testTag("home-background")) {
                        hero(onItemClick = { clicks += 1 })
                    }
                }
            }
        }
        compose.waitForIdle()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val edge = compose.onNodeWithTag(HOME_HERO_TOP_EDGE_TAG).getBoundsInRoot()
        assertEquals(0.dp, art.top)
        assertEquals(safeTop, edge.bottom - edge.top)
        assertTrue(edge.bottom <= title.top)
        assertTrue(title.top < art.bottom + 48.dp)
        compose.onNodeWithTag("home-background").performTouchInput {
            click(Offset(width / 2f, 8f))
        }
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun aLargeTypeSizeKeepsTheTitleUnderTheScrim() {
        val safeTop = HOME_PRO_PORTRAIT_TOP_DP.dp
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                NuvioTheme {
                    CompositionLocalProvider(LocalHomeContentTopInset provides safeTop) {
                        Box(Modifier.fillMaxSize().testTag("home-background")) {
                            hero()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val edge = compose.onNodeWithTag(HOME_HERO_TOP_EDGE_TAG).getBoundsInRoot()
        assertTrue(art.bottom - art.top >= 360.dp)
        assertTrue(title.top > edge.bottom)
        assertTrue(title.top < art.bottom)
    }

    @androidx.compose.runtime.Composable
    private fun hero(onItemClick: ((MetaPreview) -> Unit)? = null) {
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
            onItemClick = onItemClick,
        )
    }
}
