package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.nuvio.app.core.automation.AutomationStubs
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.SeasonPosterCastTouchProbe
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h1200dp")
class SeasonPosterCastTouchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun actorTapOpensThePersonWhenThePosterWasNeverOpened() {
        val opened = mutableListOf<Int>()
        showProbe(opened)
        compose.onNodeWithText("Actor One").performClick()
        assertEquals(listOf(900001), opened)
    }

    @Test
    fun actorTapOpensThePersonAfterThePosterIsClosedByATap() {
        val opened = mutableListOf<Int>()
        showProbe(opened)
        openPoster()
        compose.onNodeWithTag(SeasonPosterOverlayAccessibilityId, useUnmergedTree = true)
            .performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onAllNodesWithTag(SeasonPosterOverlayAccessibilityId).assertCountEquals(0)
        compose.onNodeWithText("Actor Two").performClick()
        assertEquals(listOf(900002), opened)
    }

    @Test
    fun actorTapOpensThePersonAfterThePosterIsClosedByASwipe() {
        val opened = mutableListOf<Int>()
        showProbe(opened)
        openPoster()
        compose.onNodeWithTag(SeasonPosterOverlayAccessibilityId).performTouchInput {
            down(Offset(center.x, height * 0.2f))
            moveTo(Offset(center.x, height * 0.85f))
            up()
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(SeasonPosterOverlayAccessibilityId).assertCountEquals(0)
        compose.onNodeWithText("Actor One").performClick()
        assertEquals(listOf(900001), opened)
    }

    private fun showProbe(opened: MutableList<Int>) {
        val cast = AutomationStubs.series().cast
        compose.setContent {
            NuvioTheme {
                SeasonPosterCastTouchProbe(
                    cast = cast,
                    onPersonOpened = { person -> person.tmdbId?.let(opened::add) },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Actor One").assertIsDisplayed()
        compose.onNodeWithText("Actor Two").assertIsDisplayed()
    }

    private fun openPoster() {
        compose.onNodeWithTag("season_poster_3", useUnmergedTree = true).performTouchInput {
            click(center)
            advanceEventTime(80)
            click(center)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SeasonPosterOverlayAccessibilityId).assertIsDisplayed()
    }
}
