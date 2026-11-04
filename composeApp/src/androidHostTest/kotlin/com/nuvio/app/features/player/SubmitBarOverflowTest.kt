package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.uifixture.PlayerSubmitBarFixtureContent
import com.nuvio.app.core.uifixture.longSubmitBarLabels
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scrolling side may be wider than the phone. Submit and close stay inside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1200dp-h600dp")
class SubmitBarOverflowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun submitAndCloseStayInsideFrom640To940AndNoChipIsRemoved() {
        val width = mutableIntStateOf(640)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(width.intValue.dp, 390.dp)) {
                    PlayerSubmitBarFixtureContent(longLabels = false)
                }
            }
        }
        compose.onNodeWithTag(SubmitBarSubmitTag).assertIsNotEnabled()
        for (dp in 640..940) {
            width.intValue = dp
            compose.waitForIdle()
            assertPinnedInside(dp)
            assertPreviewChips(longLabels = false)
            if (dp == 640 || dp == 667 || dp == 844 || dp == 932) {
                assertSameRow(dp)
            }
        }
    }

    @Test
    fun scrollingSideMayBeWiderThanTheViewport() {
        val labels = longSubmitBarLabels().copy(
            intro = "Introduction sequence ".repeat(8),
            recap = "Previously on this story ".repeat(4),
            preview = "Next episode preview ".repeat(4),
            playRange = "Play the marked range ".repeat(4),
        )
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 390.dp)) {
                    SegmentMarkingLayer(
                        segmentType = "preview",
                        isMovie = false,
                        episodeCode = "S2 E4",
                        year = "2024",
                        startSec = 0.0,
                        endSec = 0.0,
                        startShowsTenth = false,
                        endShowsTenth = false,
                        durationSec = 7_200.0,
                        absent = false,
                        absentAllowed = true,
                        sent = false,
                        submitting = false,
                        keyProblem = false,
                        absentLine = "",
                        addKeyLabel = "Add a key",
                        blockLine = null,
                        errorLine = null,
                        onBack = {},
                        onSegment = {},
                        onAbsent = {},
                        onSetToNow = {},
                        onNudge = { _, _ -> },
                        onAddKey = {},
                        onSubmit = {},
                        submittedTypes = setOf("intro", "recap"),
                        labels = labels,
                    )
                }
            }
        }
        assertPinnedInside(640)
        assertText(labels.intro)
        assertText(labels.recap)
        assertText(labels.preview)
        assertText(labels.credits)
        assertDescription("Runs to end")
        assertDescription(labels.playRange)
        assertText("No preview in this episode")
        assertText(labels.submit)
        assertDescription("Close")
        assertScrollingSideMayExceed(640)
    }

    @Test
    fun longLabelsAndLargeTextKeepSubmitAndCloseInside() {
        val width = mutableIntStateOf(640)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1.6f)) {
                NuvioTheme {
                    Box(Modifier.size(width.intValue.dp, 390.dp)) {
                        PlayerSubmitBarFixtureContent(longLabels = true)
                    }
                }
            }
        }
        val widths = (640..940 step 10).toSet() + setOf(667, 844, 932, 940)
        for (dp in widths.sorted()) {
            width.intValue = dp
            compose.waitForIdle()
            assertPinnedInside(dp)
            assertPreviewChips(longLabels = true)
        }
        width.intValue = 667
        compose.waitForIdle()
        assertText("1:23:45", substring = true)
        assertText("0:00:00", substring = true)
    }

    @Test
    fun introStillOffersTheZeroChipWhenTheBarIsNarrow() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(640.dp, 390.dp)) {
                    SegmentMarkingLayer(
                        segmentType = "intro",
                        isMovie = false,
                        episodeCode = "S1 E2",
                        year = null,
                        startSec = 0.0,
                        endSec = 20.0,
                        startShowsTenth = false,
                        endShowsTenth = false,
                        durationSec = 1_200.0,
                        absent = false,
                        absentAllowed = true,
                        sent = false,
                        submitting = false,
                        keyProblem = false,
                        absentLine = "",
                        addKeyLabel = "Add a key",
                        blockLine = null,
                        errorLine = null,
                        onBack = {},
                        onSegment = {},
                        onAbsent = {},
                        onSetToNow = {},
                        onNudge = { _, _ -> },
                        onAddKey = {},
                        onSubmit = {},
                        submittedTypes = setOf("recap"),
                    )
                }
            }
        }
        assertText("Intro")
        assertText("Recap")
        assertText("Credits")
        assertText("Preview")
        assertDescription("Recap Sent")
        assertDescription("Starts at 0:00")
        assertText("0:00")
        assertText("No intro")
        assertDescription("Play range")
        assertText("Submit")
        assertDescription("Close")
        assertPinnedInside(640)
    }

    private fun assertPinnedInside(widthDp: Int) {
        val container = compose.onNodeWithTag("submitPanel").getUnclippedBoundsInRoot()
        val containerWidth = (container.right - container.left).value
        assertEquals(widthDp.toFloat(), containerWidth, 1.5f, "container width at ${widthDp}dp")
        assertTrue(abs(container.left.value) < 1f, "container left ${container.left}")
        val submit = compose.onNodeWithTag(SubmitBarSubmitTag).getUnclippedBoundsInRoot()
        val close = compose.onNodeWithTag(SubmitBarCloseTag).getUnclippedBoundsInRoot()
        assertInside(submit.left.value, submit.right.value, container.left.value, container.right.value, "Submit", widthDp)
        assertInside(close.left.value, close.right.value, container.left.value, container.right.value, "Close", widthDp)
        assertTrue((close.bottom - close.top).value > 1f, "Close collapsed at ${widthDp}dp")
        assertTrue(submit.right <= close.left + 0.5.dp, "Submit overlaps close at ${widthDp}dp")
    }

    private fun assertInside(left: Float, right: Float, start: Float, end: Float, name: String, widthDp: Int) {
        assertTrue(left >= start - 0.5f, "$name left $left is outside [0, $widthDp]")
        assertTrue(right <= end + 0.5f, "$name right $right is outside [0, $widthDp]")
        assertTrue(right - left > 1f, "$name has no width at ${widthDp}dp")
    }

    private fun assertScrollingSideMayExceed(widthDp: Int) {
        val viewport = compose.onNodeWithTag(SubmitBarScrollTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val content = compose.onNodeWithTag(SubmitBarScrollContentTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val viewportWidth = viewport.right - viewport.left
        val contentWidth = content.right - content.left
        assertTrue(
            contentWidth > viewportWidth + 1.dp,
            "scrolling side $contentWidth should be allowed past the $viewportWidth viewport at ${widthDp}dp",
        )
    }

    private fun assertSameRow(widthDp: Int) {
        val chip = compose.onAllNodesWithText("Intro")[0].getUnclippedBoundsInRoot()
        val submit = compose.onNodeWithTag(SubmitBarSubmitTag).getUnclippedBoundsInRoot()
        val chipMid = (chip.top + chip.bottom) / 2
        val submitMid = (submit.top + submit.bottom) / 2
        val chipHeight = chip.bottom - chip.top
        assertTrue(
            abs((chipMid - submitMid).value) <= chipHeight.value,
            "Submit left the chip row at ${widthDp}dp",
        )
    }

    private fun assertPreviewChips(longLabels: Boolean) {
        val intro = if (longLabels) "Introduction sequence" else "Intro"
        val recap = if (longLabels) "Previously on this story" else "Recap"
        val credits = if (longLabels) "Closing credits roll" else "Credits"
        val preview = if (longLabels) "Next episode preview" else "Preview"
        val none = if (longLabels) "No preview in this episode" else "No preview"
        val play = if (longLabels) "Play the marked range" else "Play range"
        val submit = if (longLabels) "Submit timestamps" else "Submit"
        val runsToEnd = if (longLabels) "Runs to the end" else "End"
        assertText(intro)
        assertText(recap)
        assertText(credits)
        assertText(preview)
        assertDescription("$intro Sent")
        assertDescription("$recap Sent")
        assertText(none)
        assertDescription(play)
        assertText(submit)
        assertText(runsToEnd)
        assertDescription("Runs to end")
        assertDescription("Close")
        if (longLabels) {
            assertText("1:23:45", substring = true)
            assertTrue(compose.onAllNodesWithText("0:00:00", substring = true).fetchSemanticsNodes().isNotEmpty())
        } else {
            assertTrue(compose.onAllNodesWithText("0:00:00", substring = true).fetchSemanticsNodes().size >= 2)
            assertText("End must be after the start")
        }
    }

    private fun assertText(text: String, substring: Boolean = false) {
        assertTrue(
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty(),
            "missing text $text",
        )
    }

    private fun assertDescription(text: String) {
        assertTrue(
            compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty(),
            "missing description $text",
        )
    }
}
