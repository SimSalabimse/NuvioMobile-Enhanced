package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalDetailChromeTopOverride
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DETAIL_HEADER_COVER_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_ACTION_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_BACK_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_EDGE_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_TITLE_TAG
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One header. At rest the hero is full-bleed and only the buttons clear the inset.
 * After the page scrolls, the opaque cover fills that row and sits over the content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp-mdpi")
class DetailHeaderEdgeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun atRestTheHeroStaysClearAndTheButtonsSitBelowTheInset() {
        val safeTop = 59.dp
        var saves = 0
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(
                    LocalDetailChromeTopOverride provides safeTop,
                    LocalUseNativeNavigation provides true,
                    LocalNativeNavigationBarHidden provides true,
                ) {
                    DetailFloatingHeader(
                        meta = MetaDetails(id = "fixture-1", type = "series", name = "Northwind"),
                        isSaved = false,
                        progress = mutableFloatStateOf(0f),
                        onBack = {},
                        onToggleSaved = { saves += 1 },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DETAIL_HEADER_COVER_TAG).assertDoesNotExist()
        val back = compose.onNodeWithTag(DETAIL_HEADER_BACK_TAG).getBoundsInRoot()
        val action = compose.onNodeWithTag(DETAIL_HEADER_ACTION_TAG).getBoundsInRoot()
        val edge = compose.onNodeWithTag(DETAIL_HEADER_EDGE_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(DETAIL_HEADER_TITLE_TAG).getBoundsInRoot()
        assertEquals(0.dp, edge.top)
        assertTrue(edge.bottom > safeTop)
        assertTrue(edge.bottom - edge.top <= safeTop + 28.dp)
        assertTrue(edge.bottom < back.bottom)
        assertTrue(title.top < back.bottom)
        assertTrue(title.bottom > back.top)
        assertEquals(0f, com.nuvio.app.features.details.components.detailHeaderTitleAlpha(0f))
        assertEquals(0f, com.nuvio.app.features.details.components.detailHeaderCoverAlpha(0f))
        assertTrue(back.top >= safeTop)
        assertTrue(back.bottom - back.top >= 44.dp)
        assertTrue(action.bottom - action.top >= 44.dp)
        assertEquals(back.top, action.top)
        compose.onNodeWithTag(DETAIL_HEADER_ACTION_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, saves)
    }

    @Test
    fun theCoverFillsTheRowOnceThePageScrolls() {
        assertScrolledCover(safeTop = 59.dp, fontScale = 1f)
    }

    @Test
    fun aSmallPhoneCoverFillsTheRowOnceThePageScrolls() {
        assertScrolledCover(safeTop = 20.dp, fontScale = 1f)
    }

    @Test
    fun theTitleJoinsTheSameRowInsteadOfASecondStrip() {
        val safeTop = 59.dp
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(
                    LocalDetailChromeTopOverride provides safeTop,
                    LocalUseNativeNavigation provides true,
                    LocalNativeNavigationBarHidden provides true,
                ) {
                    DetailFloatingHeader(
                        meta = MetaDetails(id = "fixture-1", type = "series", name = "Northwind"),
                        isSaved = true,
                        progress = mutableFloatStateOf(1f),
                        onBack = {},
                        onToggleSaved = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val back = compose.onNodeWithTag(DETAIL_HEADER_BACK_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(DETAIL_HEADER_TITLE_TAG).assertIsDisplayed().getBoundsInRoot()
        assertTrue(title.top < back.bottom)
        assertTrue(title.bottom > back.top)
        assertTrue(title.top >= safeTop)
        assertEquals(1f, com.nuvio.app.features.details.components.detailHeaderTitleAlpha(1f))
    }

    @Test
    fun fadingTheTitleInDoesNotResizeTheHeader() {
        val safeTop = 59.dp
        val progress = mutableFloatStateOf(0f)
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalDetailChromeTopOverride provides safeTop) {
                    DetailFloatingHeader(
                        meta = MetaDetails(id = "fixture-1", type = "series", name = "Northwind"),
                        isSaved = false,
                        progress = progress,
                        onBack = {},
                        onToggleSaved = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag(DETAIL_HEADER_TAG).getBoundsInRoot()
        progress.floatValue = 1f
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag(DETAIL_HEADER_TAG).getBoundsInRoot())
    }

    @Test
    fun aSmallPhoneInsetStillClearsTheBackButton() {
        val safeTop = 20.dp
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalDetailChromeTopOverride provides safeTop) {
                    DetailFloatingHeader(
                        meta = MetaDetails(id = "fixture-1", type = "series", name = "Northwind"),
                        isSaved = false,
                        progress = mutableFloatStateOf(0f),
                        onBack = {},
                        onToggleSaved = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DETAIL_HEADER_COVER_TAG).assertDoesNotExist()
        val back = compose.onNodeWithTag(DETAIL_HEADER_BACK_TAG).getBoundsInRoot()
        assertTrue(back.top >= safeTop)
    }

    @Test
    fun largeTextKeepsTheBackButtonUnderTheInset() {
        assertScrolledCover(safeTop = 59.dp, fontScale = 2f)
    }

    private fun assertScrolledCover(safeTop: androidx.compose.ui.unit.Dp, fontScale: Float) {
        compose.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    density = 1f,
                    fontScale = fontScale,
                ),
            ) {
                NuvioTheme {
                    CompositionLocalProvider(LocalDetailChromeTopOverride provides safeTop) {
                        DetailFloatingHeader(
                            meta = MetaDetails(id = "fixture-1", type = "series", name = "Northwind"),
                            isSaved = false,
                            progress = mutableFloatStateOf(1f),
                            onBack = {},
                            onToggleSaved = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val back = compose.onNodeWithTag(DETAIL_HEADER_BACK_TAG).getBoundsInRoot()
        val cover = compose.onNodeWithTag(DETAIL_HEADER_COVER_TAG).getBoundsInRoot()
        assertEquals(0.dp, cover.top)
        assertTrue(cover.bottom >= safeTop + 56.dp)
        assertTrue(cover.bottom >= back.bottom)
        assertTrue(back.top >= safeTop)
        assertEquals(1f, com.nuvio.app.features.details.components.detailHeaderCoverAlpha(1f))
    }
}
