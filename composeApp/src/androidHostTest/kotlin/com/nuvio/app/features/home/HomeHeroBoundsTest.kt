package com.nuvio.app.features.home

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.homeContentTopInset
import com.nuvio.app.features.home.components.LocalHomeContentTopInset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phone portrait. The picture starts at the window's top inset.
 * Nothing in the hero sits in the status band. The inset comes from the same reader the screen uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp")
class HomeHeroBoundsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theArtworkStartsAtTheSafeAreaAndTheTitleStaysInsideIt() {
        assertEquals(0f, homeHeroTopPaddingDp(cardStyle = false, contentTopDp = HOME_PRO_PORTRAIT_TOP_DP))
        assertEquals(0f, homeHeroTopPaddingDp(cardStyle = false, contentTopDp = HOME_SMALL_PORTRAIT_TOP_DP))
        assertEquals(0f, homeHeroTopPaddingDp(cardStyle = true, contentTopDp = 59f))
        assertEquals(0f, homeHeroTopPaddingDp(cardStyle = true, contentTopDp = 20f))
        val safeTop = HOME_PRO_PORTRAIT_TOP_DP.dp
        compose.setContent {
            framed(safeTop) {
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
        compose.waitForIdle()
        val window = compose.onNodeWithTag("home-window").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val logo = compose.onNodeWithTag(HOME_HERO_LOGO_TAG).getBoundsInRoot()
        val badges = compose.onNodeWithTag(HOME_HERO_BADGES_TAG).getBoundsInRoot()
        assertTrue(art.top >= window.top + safeTop)
        assertTrue(logo.top >= art.top)
        assertTrue(badges.top >= art.top)
        assertTrue(title.top >= art.top)
        assertTrue(art.bottom - art.top > 402.dp * 9f / 16f)
        assertTrue(title.bottom <= art.bottom + 1.dp)
    }

    @Test
    fun thePageDotsSitBelowTheSafeArea() {
        val safeTop = HOME_PRO_PORTRAIT_TOP_DP.dp
        compose.setContent {
            framed(safeTop) {
                HomeHeroSection(
                    items = listOf(preview("one"), preview("two")),
                    viewportHeight = 874.dp,
                    trailerPlaybackEnabled = false,
                )
            }
        }
        compose.waitForIdle()
        val window = compose.onNodeWithTag("home-window").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val dots = compose.onNodeWithTag(HOME_HERO_DOTS_TAG).getBoundsInRoot()
        assertEquals(0, compose.onAllNodesWithTag("home-hero-top-edge").fetchSemanticsNodes().size)
        assertTrue(art.top >= window.top + safeTop)
        assertTrue(dots.top >= art.top)
        assertTrue(title.top >= art.top)
        assertTrue(title.bottom <= art.bottom + 1.dp)
        assertTrue(dots.bottom <= art.bottom + 1.dp)
    }

    @Test
    fun aTallSafeAreaKeepsTheArtworkBelowIt() {
        val safeTop = 400.dp
        compose.setContent {
            framed(safeTop) { hero() }
        }
        compose.waitForIdle()
        val window = compose.onNodeWithTag("home-window").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        assertEquals(0, compose.onAllNodesWithTag("home-hero-top-edge").fetchSemanticsNodes().size)
        assertTrue(art.top >= window.top + safeTop)
        assertTrue(title.top >= art.top)
        assertTrue(title.bottom <= art.bottom + 1.dp)
    }

    @Test
    fun aZeroInsetDoesNotAddABandOrMoveTheTitle() {
        compose.setContent {
            framed(0.dp) { hero() }
        }
        compose.waitForIdle()
        val window = compose.onNodeWithTag("home-window").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        assertEquals(0, compose.onAllNodesWithTag("home-hero-top-edge").fetchSemanticsNodes().size)
        assertEquals(window.top, art.top)
        assertTrue(art.bottom - art.top >= 360.dp)
        assertTrue(title.top >= art.top)
        assertTrue(title.bottom <= art.bottom + 1.dp)
    }

    @Test
    fun theStatusBandIsPlainBackgroundAndTheArtStartsBelowIt() {
        val safeTop = HOME_PRO_PORTRAIT_TOP_DP.dp
        var clicks = 0
        compose.setContent {
            framed(safeTop) { hero(onItemClick = { clicks += 1 }) }
        }
        compose.waitForIdle()
        val window = compose.onNodeWithTag("home-window").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        assertEquals(0, compose.onAllNodesWithTag("home-hero-top-edge").fetchSemanticsNodes().size)
        assertTrue(art.top >= window.top + safeTop)
        assertTrue(title.top >= art.top)
        compose.onNodeWithTag("home-window").performTouchInput {
            click(Offset(width / 2f, 8f))
        }
        compose.waitForIdle()
        assertEquals(0, clicks)
        compose.onNodeWithTag(HOME_HERO_ART_TAG).performTouchInput {
            click(Offset(width / 2f, height / 2f))
        }
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    @Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp")
    fun aTallCarouselClearsTheIslandOn16ProAtRestAndAfterASwipe() {
        assertCopyClearsTheIsland(safeTop = HOME_PRO_PORTRAIT_TOP_DP.dp, width = 402.dp, height = 874.dp)
    }

    @Test
    @Config(sdk = [34], application = Application::class, qualifiers = "w375dp-h667dp")
    fun aTallCarouselClearsTheStatusBarOnSeAtRestAndAfterASwipe() {
        assertCopyClearsTheIsland(safeTop = HOME_SMALL_PORTRAIT_TOP_DP.dp, width = 375.dp, height = 667.dp)
    }

    @Test
    fun aLargeTypeSizeKeepsTheTitleBelowTheSafeArea() {
        val safeTop = HOME_PRO_PORTRAIT_TOP_DP.dp
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                framed(safeTop) { hero() }
            }
        }
        compose.waitForIdle()
        val window = compose.onNodeWithTag("home-window").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val logo = compose.onNodeWithTag(HOME_HERO_LOGO_TAG).getBoundsInRoot()
        assertEquals(0, compose.onAllNodesWithTag("home-hero-top-edge").fetchSemanticsNodes().size)
        assertTrue(art.top >= window.top + safeTop)
        assertTrue(logo.top >= art.top)
        assertTrue(title.top >= art.top)
        assertTrue(title.bottom <= art.bottom + 1.dp)
    }

    private fun assertCopyClearsTheIsland(
        safeTop: androidx.compose.ui.unit.Dp,
        width: androidx.compose.ui.unit.Dp,
        height: androidx.compose.ui.unit.Dp,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 3f)) {
                framed(safeTop) {
                    HomeHeroSection(
                        items = listOf(tallPreview("tall-1"), tallPreview("tall-2")),
                        viewportHeight = height,
                        trailerPlaybackEnabled = false,
                    )
                }
            }
        }
        compose.waitForIdle()
        val window = compose.onNodeWithTag("home-window").getBoundsInRoot()
        val art = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        val copy = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val dots = compose.onNodeWithTag(HOME_HERO_DOTS_TAG).getBoundsInRoot()
        assertTrue(art.top >= window.top + safeTop, "art top ${art.top}")
        assertTrue(art.right - art.left >= width - 2.dp, "art width ${art.right - art.left} expected about $width")
        assertTrue(copy.top >= art.top, "copy top ${copy.top}")
        assertTrue(dots.top >= art.top)
        assertTrue(copy.bottom <= art.bottom + 1.dp)
        assertTrue(copy.bottom - copy.top >= 180.dp, "copy height ${copy.bottom - copy.top}")
        compose.onNodeWithTag(HOME_HERO_ART_TAG).performTouchInput {
            swipeLeft(startX = width.toPx() * 0.8f, endX = width.toPx() * 0.2f)
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val artAfter = compose.onNodeWithTag(HOME_HERO_ART_TAG).getBoundsInRoot()
        assertTrue(artAfter.top >= window.top + safeTop)
        assertTrue(after.top >= artAfter.top, "after swipe ${after.top}")
        assertTrue(after.bottom <= artAfter.bottom + 1.dp)
    }

    @Composable
    private fun framed(safeTop: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalHomeContentTopInset provides safeTop) {
            NuvioTheme {
                val inset = homeContentTopInset()
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .testTag("home-window"),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(top = inset)
                            .clipToBounds(),
                    ) {
                        content()
                    }
                }
            }
        }
    }

    private fun tallPreview(id: String) = MetaPreview(
        id = id,
        type = "movie",
        name = "Northwind Over the Frozen Reach",
        description = "A long synopsis that wraps for several lines so the hero copy is taller than a short picture. The title, the year, and this paragraph have to stay under the island.",
        releaseInfo = "2024",
    )

    private fun preview(id: String) = MetaPreview(
        id = id,
        type = "movie",
        name = "Northwind",
        description = "Synthetic layout title.",
        releaseInfo = "2024",
    )

    @androidx.compose.runtime.Composable
    private fun hero(onItemClick: ((MetaPreview) -> Unit)? = null) {
        HomeHeroSection(
            items = listOf(
                preview("fixture-preview-1"),
            ),
            viewportHeight = 874.dp,
            trailerPlaybackEnabled = false,
            onItemClick = onItemClick,
        )
    }
}
