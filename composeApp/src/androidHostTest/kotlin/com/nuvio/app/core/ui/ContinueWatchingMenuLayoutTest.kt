package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.emptyBackdrop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Continue Watching long-press menu stays above the floating tab bar.
 * One watched row. A row past the fold can be scrolled into reach.
 */
@RunWith(RobolectricTestRunner::class)
class ContinueWatchingMenuLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp")
    fun theMenuClearsTheTabBarOn16Pro() {
        assertMenuClearsTheTabBar(heightDp = 874f, safeBottomDp = 34f, safeTopDp = 59f)
    }

    @Test
    @Config(sdk = [34], application = Application::class, qualifiers = "w375dp-h667dp")
    fun theMenuClearsTheTabBarOnSe() {
        assertMenuClearsTheTabBar(heightDp = 667f, safeBottomDp = 0f, safeTopDp = 20f)
    }

    private fun assertMenuClearsTheTabBar(heightDp: Float, safeBottomDp: Float, safeTopDp: Float) {
        val labels = listOf(
            "Details",
            "Mark as watched",
            "Watch privately",
            "Play manually",
            "Start from beginning",
            "Download",
            "Finish this series",
            "Auto-skip",
            "Remove",
        )
        val clearance = maxOf(
            floatingTabContentBottomInsetDp(nativeTabs = true, safeAreaBottomDp = safeBottomDp),
            floatingTabContentBottomInsetDp(nativeTabs = false, safeAreaBottomDp = safeBottomDp),
        ).dp
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalWindowSafeTopInset provides safeTopDp.dp) {
                Box(Modifier.fillMaxSize().testTag("menu-window")) {
                    NuvioPosterZoomActionOverlay(
                        imageUrl = null,
                        title = "Northwind",
                        subtitle = "S1 E2",
                        anchor = null,
                        actions = labels.map { label ->
                            PosterZoomOverlayAction(
                                icon = when (label) {
                                    "Remove" -> Icons.Default.Delete
                                    "Details" -> Icons.Default.Info
                                    "Play manually" -> Icons.Default.PlayArrow
                                    else -> Icons.Default.CheckCircle
                                },
                                label = label,
                                onSelected = {},
                            )
                        },
                        backdrop = emptyBackdrop(),
                        onDismissed = {},
                        modifier = Modifier.fillMaxSize(),
                        bottomClearance = clearance,
                    )
                }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val window = compose.onNodeWithTag("menu-window").getBoundsInRoot()
        val menu = compose.onNodeWithTag(POSTER_ZOOM_MENU_TAG).getBoundsInRoot()
        val safeTop = safeTopDp.dp
        assertTrue(menu.top >= window.top + safeTop, "menu top ${menu.top}")
        assertTrue(
            menu.bottom <= heightDp.dp - clearance + 1.dp,
            "menu bottom ${menu.bottom} should sit above the ${clearance} tab clearance",
        )
        val previews = compose.onAllNodes(hasTestTag(POSTER_ZOOM_PREVIEW_TAG)).fetchSemanticsNodes()
        if (previews.isNotEmpty()) {
            val preview = compose.onNodeWithTag(POSTER_ZOOM_PREVIEW_TAG).getBoundsInRoot()
            assertTrue(preview.top >= window.top + safeTop, "preview top ${preview.top}")
            assertTrue(preview.bottom <= menu.top + 1.dp)
        }
        assertEquals(1, compose.onAllNodesWithText("Mark as watched").fetchSemanticsNodes().size)
        labels.forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
        val remove = compose.onNodeWithText("Remove", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(
            remove.top >= window.top + safeTop && remove.bottom > remove.top,
            "Remove $remove menu $menu window $window safeTop $safeTop",
        )
        assertTrue(remove.top >= menu.top - 1.dp, "Remove top ${remove.top}")
        assertTrue(remove.bottom <= menu.bottom + 1.dp, "Remove bottom ${remove.bottom}")
        assertTrue(
            remove.bottom <= heightDp.dp - clearance + 1.dp,
            "Remove bottom ${remove.bottom} should sit above the tab bar",
        )
    }
}
