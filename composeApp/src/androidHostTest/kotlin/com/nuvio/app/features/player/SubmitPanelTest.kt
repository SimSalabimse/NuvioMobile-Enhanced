package com.nuvio.app.features.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1024dp-h480dp-land")
class SubmitPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun phoneLandscapeShowsIntroCreditsAndNoneWithoutClipping() {
        val type = mutableStateOf("intro")
        val absent = mutableStateOf(false)
        val start = mutableStateOf<Double?>(209.0)
        val end = mutableStateOf<Double?>(231.0)
        val handle = mutableStateOf(MarkHandle.Start)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(1024.dp, 480.dp)) {
                    Panel(
                        type = type.value,
                        absent = absent.value,
                        isMovie = false,
                        year = null,
                        episodeCode = "S1 E22",
                        startSec = start.value,
                        endSec = end.value,
                        onSegment = { tapped ->
                            when (segmentTap(type.value, tapped, absent.value)) {
                                SegmentTap.Stay -> Unit
                                SegmentTap.ReturnToEditing -> absent.value = false
                                SegmentTap.Switch -> {
                                    type.value = markSegmentId(tapped)
                                    absent.value = false
                                }
                            }
                        },
                        onAbsent = { absent.value = absentAfterNoneChip() },
                        onNudge = { which, delta ->
                            handle.value = which
                            if (which == MarkHandle.Start) {
                                start.value = nudgeMarkSeconds(start.value, delta, 3_000.0)
                            } else {
                                end.value = nudgeMarkSeconds(end.value, delta, 3_000.0)
                            }
                        },
                    )
                }
            }
        }
        compose.onNodeWithText("Intro").assertIsDisplayed()
        compose.onNodeWithText("Recap").assertIsDisplayed()
        compose.onNodeWithText("Credits").assertIsDisplayed()
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithText("No intro").assertIsDisplayed()
        compose.onNodeWithText("0:03:29", substring = true).assertIsDisplayed()
        compose.onNodeWithText("0:03:51", substring = true).assertIsDisplayed()
        compose.onNodeWithText("22 s ✓").assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsEnabled()
        compose.onAllNodesWithText("Set to now").assertCountEquals(0)
        compose.onAllNodesWithText("\u22125").assertCountEquals(0)
        compose.onNodeWithContentDescription("Play range").assertIsEnabled()
        compose.onNodeWithContentDescription("Copy link").assertIsEnabled()
        compose.onNodeWithContentDescription("Starts at 0:00").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("More").assertCountEquals(0)

        compose.onNodeWithText("0:03:29", substring = true).performClick()
        compose.onNodeWithText("Set to now").assertIsDisplayed()
        compose.onNodeWithText("\u22125").assertIsDisplayed()
        compose.onNodeWithText("\u22125").performClick()
        compose.onNodeWithText("0:03:24", substring = true).assertIsDisplayed()

        compose.onNodeWithText("0:03:24", substring = true).performClick()
        compose.onAllNodesWithText("Set to now").assertCountEquals(0)
        compose.onAllNodesWithText("\u22125").assertCountEquals(0)

        compose.onNodeWithText("No intro").performClick()
        compose.onNodeWithText("No intro · S1 E22").assertIsDisplayed()
        compose.onAllNodesWithText("0:03:24").assertCountEquals(0)
        compose.onAllNodesWithText("Set to now").assertCountEquals(0)
        compose.onAllNodesWithText("\u22125").assertCountEquals(0)

        compose.onNodeWithText("Intro").performClick()
        compose.onNodeWithText("No intro").assertIsDisplayed()
        compose.onNodeWithText("0:03:24", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsDisplayed()
        compose.onAllNodesWithText("Set to now").assertCountEquals(0)

        compose.onNodeWithText("Credits").performClick()
        compose.onNodeWithText("No credits").assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsDisplayed()
        compose.onAllNodesWithText("Credits · S1 E22").assertCountEquals(0)
    }

    @Test
    fun shortErrorsStayWholeAndAStreamEndStaysValid() {
        val start = mutableStateOf(40.0)
        val end = mutableStateOf(10.0)
        val duration = mutableStateOf(3_000.0)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(1024.dp, 480.dp)) {
                    Panel(
                        type = "intro",
                        absent = false,
                        isMovie = false,
                        year = null,
                        episodeCode = "S1 E22",
                        startSec = start.value,
                        endSec = end.value,
                        durationSec = duration.value,
                        onSegment = {},
                        onAbsent = {},
                        onNudge = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithText("End must be after the start").assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsNotEnabled()
        compose.onAllNodesWithText("…").assertCountEquals(0)

        start.value = 0.0
        end.value = 4.9
        compose.waitForIdle()
        compose.onNodeWithText("4.9 s · Intro must be 5–300 s").assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsNotEnabled()

        end.value = 17.0
        duration.value = 12.0
        compose.waitForIdle()
        compose.onNodeWithText("17 s · Past the end of the video").assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsNotEnabled()

        end.value = 12.0
        compose.waitForIdle()
        compose.onNodeWithText("12 s ✓").assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsEnabled()
        compose.onNodeWithText("0:00:12", substring = true).assertIsDisplayed()

        end.value = 12.001
        compose.waitForIdle()
        compose.onNodeWithText("12 s ✓").assertIsDisplayed()
        compose.onAllNodesWithText("Past the end of the video").assertCountEquals(0)

        end.value = 12.0 + MarkDurationSlopSec
        compose.waitForIdle()
        compose.onNodeWithText("12 s ✓").assertIsDisplayed()
        compose.onNodeWithText("Submit").assertIsEnabled()
    }

    @Test
    fun moviesKeepIntroAndCreditsAndIntroDbRefusesNone() {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(844.dp, 390.dp)) {
                    Panel(
                        type = "intro",
                        absent = true,
                        isMovie = true,
                        year = "2010",
                        episodeCode = null,
                        startSec = 12.0,
                        endSec = 40.0,
                        absentAllowed = false,
                        absentLine = "IntroDB has no empty-segment submit.",
                        onSegment = {},
                        onAbsent = {},
                        onNudge = { _, _ -> },
                    )
                }
            }
        }
        compose.onNodeWithText("Intro").assertIsDisplayed()
        compose.onNodeWithText("Credits").assertIsDisplayed()
        compose.onAllNodesWithText("Recap").assertCountEquals(0)
        compose.onAllNodesWithText("Preview").assertCountEquals(0)
        compose.onNodeWithText("IntroDB has no empty-segment submit.").assertIsDisplayed()
        compose.onNodeWithText("No intro · Movie · 2010").assertIsNotEnabled()
        compose.onAllNodesWithText("Submit").assertCountEquals(0)
    }
}

@androidx.compose.runtime.Composable
private fun Panel(
    type: String,
    absent: Boolean,
    isMovie: Boolean,
    year: String?,
    episodeCode: String?,
    startSec: Double?,
    endSec: Double?,
    onSegment: (String) -> Unit,
    onAbsent: () -> Unit,
    onNudge: (MarkHandle, Double) -> Unit,
    absentAllowed: Boolean = true,
    absentLine: String = "",
    durationSec: Double = 3_000.0,
    errorLine: String? = null,
) {
    SegmentMarkingLayer(
        segmentType = type,
        isMovie = isMovie,
        episodeCode = episodeCode,
        year = year,
        startSec = startSec,
        endSec = endSec,
        startShowsTenth = false,
        endShowsTenth = false,
        durationSec = durationSec,
        absent = absent,
        absentAllowed = absentAllowed,
        sent = false,
        submitting = false,
        keyProblem = false,
        absentLine = absentLine,
        addKeyLabel = "Add a key",
        blockLine = null,
        errorLine = errorLine,
        onBack = {},
        onSegment = onSegment,
        onAbsent = onAbsent,
        onSetToNow = {},
        onNudge = onNudge,
        onAddKey = {},
        onSubmit = {},
    )
}
