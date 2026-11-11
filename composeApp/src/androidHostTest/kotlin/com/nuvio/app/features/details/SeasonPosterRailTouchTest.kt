package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.SeasonPosterPlacement
import com.nuvio.app.features.details.components.SeasonPosterRailTouchProbe
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h800dp")
class SeasonPosterRailTouchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun doubleTapAfterScrollKeepsSelectionAndClosesOntoThatSeason() {
        val placement = SeasonPosterPlacement()
        compose.setContent {
            NuvioTheme {
                SeasonPosterRailTouchProbe(placement = placement)
            }
        }
        compose.waitForIdle()
        scrollToSeason(12)
        compose.onNodeWithTag("season_poster_12", useUnmergedTree = true).performTouchInput {
            click(center)
            advanceEventTime(80)
            click(center)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("opened_season").assertTextEquals("opened:12")
        compose.onNodeWithTag("selected_season").assertTextEquals("selected:1")
        val row = placement.rowViewport
        val live = placement.bounds[12]
        assertTrue(row != null && live != null)
        val end = seasonPosterCloseFrame(
            progress = 0f,
            liveBounds = live,
            rowViewport = row,
            expandedLeft = 10f,
            expandedTop = 20f,
            expandedWidth = 300f,
            expandedHeight = 450f,
            reduceMotion = false,
            swiping = false,
        )
        assertTrue(end.zooms)
        assertEquals(live.left, end.left)
        assertEquals(live.top, end.top)
        assertEquals(live.width, end.width)
        assertEquals(live.height, end.height)
        assertTrue(seasonPosterCloseClipsToRow(closing = true, zooms = end.zooms))
        val neighbour = placement.bounds[11]
        if (neighbour != null) {
            assertTrue(seasonPosterCloseBounds(neighbour, row) != live)
        }
        compose.onNodeWithTag(SeasonPosterOverlayAccessibilityId, useUnmergedTree = true)
            .performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("selected_season").assertTextEquals("selected:1")
        compose.onAllNodesWithTag(SeasonPosterOverlayAccessibilityId).assertCountEquals(0)
    }

    @Test
    fun partlyVisibleThumbnailFadesInsteadOfShrinking() {
        val row = placementRow()
        val partial = Rect(
            left = row.left - 40f,
            top = row.top + 4f,
            right = row.left + 60f,
            bottom = row.bottom - 4f,
        )
        assertNull(seasonPosterCloseBounds(partial, row))
        val faded = seasonPosterCloseFrame(
            progress = 0f,
            liveBounds = partial,
            rowViewport = row,
            expandedLeft = 12f,
            expandedTop = 40f,
            expandedWidth = 300f,
            expandedHeight = 450f,
            reduceMotion = false,
            swiping = false,
        )
        assertEquals(false, faded.zooms)
        assertEquals(12f, faded.left)
    }

    @Test
    fun singleTapSelectsAfterTheDoubleTapWindow() {
        val placement = SeasonPosterPlacement()
        compose.setContent {
            NuvioTheme {
                SeasonPosterRailTouchProbe(placement = placement)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("season_poster_2", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(SeasonPosterDoubleTapWindowMillis + 50)
        compose.waitForIdle()
        compose.onNodeWithTag("selected_season").assertTextEquals("selected:2")
    }

    private fun scrollToSeason(season: Int) {
        compose.onNodeWithTag("season_poster_row", useUnmergedTree = true).performScrollToKey(season)
        compose.waitForIdle()
        compose.onNodeWithTag(seasonPosterAccessibilityId(season), useUnmergedTree = true).assertIsDisplayed()
    }

    private fun placementRow(): Rect {
        val placement = SeasonPosterPlacement()
        compose.setContent {
            NuvioTheme {
                SeasonPosterRailTouchProbe(placement = placement)
            }
        }
        compose.waitForIdle()
        return checkNotNull(placement.rowViewport)
    }
}
