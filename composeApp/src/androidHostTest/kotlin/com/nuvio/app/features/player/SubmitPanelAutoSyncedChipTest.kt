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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.player.skip.FlagSubmissionRecord
import com.nuvio.app.features.player.skip.flagChecksForTitle
import com.nuvio.app.features.player.skip.rejectedFlagTypesForTitle
import com.nuvio.app.features.player.skip.submittedFlagTypesForTitle
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h480dp-land")
class SubmitPanelAutoSyncedChipTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun autoSyncedChipFollowsSegmentTypeAndLeavesTheOtherControlsAlone() {
        val type = mutableStateOf("intro")
        val movie = mutableStateOf(false)
        val absent = mutableStateOf(false)
        val start = mutableStateOf(12.0)
        val end = mutableStateOf(40.0)
        val duration = mutableStateOf(3_000.0)
        val submitted = mutableStateOf(setOf<String>())
        val synced = mutableStateOf(setOf("intro", "outro"))
        val previews = mutableStateOf(0)
        val copies = mutableStateOf(0)
        val flagLayout = mutableStateOf(PlayerToolbarLayout.decode(null))
        val flagLegacy = mutableStateOf(false)
        val flagPhase = mutableStateOf(false)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.size(1280.dp, 480.dp)) {
                    if (!flagPhase.value) {
                        SegmentMarkingLayer(
                            segmentType = type.value,
                            isMovie = movie.value,
                            episodeCode = "S1 E22",
                            year = "2024",
                            startSec = start.value,
                            endSec = end.value,
                            startShowsTenth = false,
                            endShowsTenth = false,
                            durationSec = duration.value,
                            absent = absent.value,
                            absentAllowed = true,
                            sent = false,
                            submitting = false,
                            keyProblem = false,
                            absentLine = "",
                            addKeyLabel = "Add a key",
                            blockLine = null,
                            errorLine = null,
                            onBack = {},
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
                            onAbsent = { absent.value = true },
                            onSetToNow = {},
                            onNudge = { _, _ -> },
                            onAddKey = {},
                            onSubmit = {},
                            submittedTypes = submitted.value,
                            onStartAtZero = { start.value = 0.0 },
                            onRunsToEnd = { end.value = runsToEndSec(duration.value) },
                            onPreview = { previews.value += 1 },
                            autoSyncedTypes = synced.value,
                        )
                    } else {
                        PlayerToolbar(
                            isLocked = false,
                            onLockToggle = {},
                            onBack = {},
                            onSubmitIntroClick = {}.takeIf {
                                submitIntroFlagShown(
                                    legacyLayout = flagLegacy.value,
                                    layout = flagLayout.value,
                                )
                            },
                        )
                    }
                }
            }
        }

        val episodeStates = listOf(
            "intro" to true,
            "recap" to false,
            "outro" to true,
            "preview" to false,
        )
        episodeStates.forEach { (segment, shown) ->
            type.value = segment
            compose.waitForIdle()
            assertChip(shown)
            if (segment == "intro" || segment == "recap") {
                compose.onNodeWithContentDescription("Starts at 0:00").assertIsDisplayed()
                compose.onAllNodesWithContentDescription("Runs to end").assertCountEquals(0)
            } else {
                compose.onAllNodesWithContentDescription("Starts at 0:00").assertCountEquals(0)
                compose.onNodeWithContentDescription("Runs to end").assertIsDisplayed()
            }
            compose.onNodeWithContentDescription("Play range").assertIsEnabled()
            compose.onAllNodesWithContentDescription("Copy link").assertCountEquals(0)
        }

        type.value = "intro"
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Starts at 0:00").performClick()
        compose.waitForIdle()
        assertEquals(0.0, start.value)
        assertChip(shown = true)
        compose.onNodeWithText("0:00:00", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Play range").performClick()
        assertEquals(1, previews.value)

        end.value = 0.0
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Play range").assertIsNotEnabled()
        assertChip(shown = true)

        start.value = 10.0
        end.value = 40.0
        type.value = "outro"
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Runs to end").assertIsNotSelected().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Runs to end").assertIsSelected()
        assertEquals(duration.value, end.value)
        assertChip(shown = true)

        end.value = 80.0
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Runs to end").assertIsNotSelected()

        absent.value = true
        type.value = "intro"
        compose.waitForIdle()
        assertChip(shown = true)
        compose.onAllNodesWithContentDescription("Starts at 0:00").assertCountEquals(0)
        compose.onNodeWithContentDescription("Play range").assertIsNotEnabled()
        absent.value = false

        val records = listOf(
            FlagSubmissionRecord(42, "tv", 1, 3, "intro", "accepted"),
            FlagSubmissionRecord(42, "tv", 1, 3, "recap", "pending"),
            FlagSubmissionRecord(42, "tv", 1, 3, "credits", "rejected"),
            FlagSubmissionRecord(42, "tv", 1, 3, "preview", "accepted"),
        )
        val remote = submittedFlagTypesForTitle(records, 42, isMovie = false, season = 1, episode = 3)
        val rejected = rejectedFlagTypesForTitle(records, 42, isMovie = false, season = 1, episode = 3)
        submitted.value = flagChecksForTitle(
            ledgerTypes = setOf("outro"),
            sessionTypes = setOf("credits"),
            remoteTypes = remote,
            rejectedTypes = rejected,
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Intro Sent").assertIsDisplayed()
        compose.onNodeWithContentDescription("Recap Sent").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview Sent").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Credits Sent").assertCountEquals(0)
        assertChip(shown = true)

        synced.value = setOf("intro")
        movie.value = true
        type.value = "intro"
        compose.waitForIdle()
        assertChip(shown = true)
        compose.onNodeWithText("Recap").assertIsDisplayed()
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithText("Credits").performClick()
        compose.waitForIdle()
        assertEquals("outro", type.value)
        assertChip(shown = false)
        compose.onNodeWithContentDescription("Runs to end").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play range").assertIsEnabled()

        val savedWithoutSubmitIntro = PlayerToolbarLayout.Default.encode()
            .split(',')
            .filterNot { it.substringBefore(':') == PlayerToolbarButton.SUBMIT_INTRO.storageId }
            .joinToString(",")
        val untouched = PlayerToolbarLayout.decode(savedWithoutSubmitIntro)
        val off = untouched.withVisibility(PlayerToolbarButton.SUBMIT_INTRO, false)
        val onAgain = off.withVisibility(PlayerToolbarButton.SUBMIT_INTRO, true)
        val flagCases = listOf(
            PlayerToolbarLayout.decode(null) to false to true,
            untouched to false to true,
            off to false to false,
            onAgain to false to true,
            off to true to true,
        )
        flagPhase.value = true
        flagCases.forEach { (layoutAndLegacy, shown) ->
            val (layout, legacy) = layoutAndLegacy
            flagLayout.value = layout
            flagLegacy.value = legacy
            compose.waitForIdle()
            if (shown) {
                compose.onNodeWithContentDescription("Submit Intro").assertIsDisplayed()
            } else {
                compose.onAllNodesWithContentDescription("Submit Intro").assertCountEquals(0)
            }
        }
    }

    private fun assertChip(shown: Boolean) {
        val nodes = compose.onAllNodesWithContentDescription("Auto synced")
        if (shown) nodes.assertCountEquals(1) else nodes.assertCountEquals(0)
        if (shown) compose.onNodeWithContentDescription("Auto synced").assertIsDisplayed()
    }
}
