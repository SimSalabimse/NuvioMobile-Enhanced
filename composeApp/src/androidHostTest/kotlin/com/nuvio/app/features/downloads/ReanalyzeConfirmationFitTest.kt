package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DisplaySafeInsets
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.uifixture.UiFixtureCatalog
import com.nuvio.app.features.analysis.MediaAnalysisFiles
import com.nuvio.app.features.analysis.MediaAnalysisScheduler
import com.nuvio.app.features.analysis.MediaAnalysisStatus
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.details.ChangedSurfaceTextScale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The production confirmation, at the default size and at AX5, in both orientations.
 * The title is not shortened. Cancel does not analyse. The buttons stay in the safe area.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1000dp-h1000dp")
class ReanalyzeConfirmationFitTest {
    @get:Rule
    val compose = createComposeRule()

    private val episodeId = "fixture-dl-railway-1"

    @Before
    fun initialize() {
        prepareReanalyzeItems(episodeId)
    }

    @After
    fun clear() {
        MediaAnalysisScheduler.resetForTest()
        DownloadsRepository.clearLocalState()
    }

    @Test
    fun shortTitleStaysCompactInPortraitAndLandscape() {
        val label = "Show S3E13"
        val portrait = DisplaySafeInsets(top = 59.dp, bottom = 34.dp)
        val landscape = DisplaySafeInsets(bottom = 21.dp, left = 59.dp)
        val harness = harness(label)
        harness.show(402.dp, 874.dp, portrait)
        assertFits(402.dp, 874.dp, portrait, label, compact = true)
        harness.show(874.dp, 402.dp, landscape)
        assertFits(874.dp, 402.dp, landscape, label, compact = true)
    }

    @Test
    fun longTitleScrollsAndCancelDoesNotChangeTheEpisode() {
        val label = "${UiFixtureCatalog.LONG_TITLE} S1E1"
        val portrait = DisplaySafeInsets(top = 59.dp, bottom = 34.dp)
        val landscape = DisplaySafeInsets(bottom = 21.dp, left = 59.dp)
        val harness = harness(label)
        harness.show(402.dp, 874.dp, portrait)
        assertFits(402.dp, 874.dp, portrait, label, compact = false)
        val revision = MediaAnalysisStatus.revision.value
        compose.onNodeWithTag(REANALYZE_CANCEL_TAG, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(revision, MediaAnalysisStatus.revision.value)
        harness.present()
        harness.show(874.dp, 402.dp, landscape)
        assertFits(874.dp, 402.dp, landscape, label, compact = false)
    }

    private fun harness(label: String): ConfirmationHarness {
        var width by mutableStateOf(402.dp)
        var height by mutableStateOf(874.dp)
        var safe by mutableStateOf(DisplaySafeInsets(top = 59.dp, bottom = 34.dp))
        var target by mutableStateOf<ReanalyzeTarget?>(ReanalyzeTarget(label, listOf(episodeId)))
        compose.setContent {
            NuvioTheme {
                ChangedSurfaceTextScale {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.size(width, height).testTag("viewport")) {
                            ReanalyzeModal(
                                target = target,
                                onDismiss = { target = null },
                                safeInsets = safe,
                            )
                        }
                    }
                }
            }
        }
        return ConfirmationHarness(
            show = { nextWidth, nextHeight, nextSafe ->
                width = nextWidth
                height = nextHeight
                safe = nextSafe
                target = ReanalyzeTarget(label, listOf(episodeId))
                compose.waitForIdle()
            },
            present = {
                target = ReanalyzeTarget(label, listOf(episodeId))
                compose.waitForIdle()
            },
        )
    }
}

private class ConfirmationHarness(
    val show: (Dp, Dp, DisplaySafeInsets) -> Unit,
    val present: () -> Unit,
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1000dp-h1000dp", fontScale = 3.12f)
class ReanalyzeConfirmationAx5Test {
    @get:Rule
    val compose = createComposeRule()

    private val episodeId = "fixture-dl-railway-1"

    @Before
    fun initialize() {
        prepareReanalyzeItems(episodeId)
    }

    @After
    fun clear() {
        MediaAnalysisScheduler.resetForTest()
        DownloadsRepository.clearLocalState()
    }

    @Test
    fun ax5LongAndShortTitlesKeepBothActionsInPortraitAndLandscape() {
        val longLabel = "${UiFixtureCatalog.LONG_TITLE} S1E1"
        val portrait = DisplaySafeInsets(top = 59.dp, bottom = 34.dp)
        val landscape = DisplaySafeInsets(bottom = 21.dp, left = 59.dp)
        var width by mutableStateOf(402.dp)
        var height by mutableStateOf(874.dp)
        var safe by mutableStateOf(portrait)
        var label by mutableStateOf(longLabel)
        var dismissed by mutableIntStateOf(0)
        var target by mutableStateOf<ReanalyzeTarget?>(ReanalyzeTarget(label, listOf(episodeId)))
        compose.setContent {
            NuvioTheme {
                ChangedSurfaceTextScale {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.size(width, height).testTag("viewport")) {
                            ReanalyzeModal(
                                target = target,
                                onDismiss = {
                                    dismissed += 1
                                    target = null
                                },
                                safeInsets = safe,
                            )
                        }
                    }
                }
            }
        }
        listOf(
            Triple(402.dp, 874.dp, portrait) to longLabel,
            Triple(874.dp, 402.dp, landscape) to longLabel,
            Triple(402.dp, 874.dp, portrait) to "Show S3E13",
            Triple(874.dp, 402.dp, landscape) to "Show S3E13",
        ).forEach { (frame, nextLabel) ->
            width = frame.first
            height = frame.second
            safe = frame.third
            label = nextLabel
            val seen = dismissed
            target = ReanalyzeTarget(nextLabel, listOf(episodeId))
            compose.waitForIdle()
            assertFits(compose, frame.first, frame.second, frame.third, nextLabel, compact = false)
            val revision = MediaAnalysisStatus.revision.value
            compose.onNodeWithTag(REANALYZE_CANCEL_TAG, useUnmergedTree = true).performClick()
            compose.waitForIdle()
            assertEquals(seen + 1, dismissed)
            assertEquals(revision, MediaAnalysisStatus.revision.value)
        }
    }
}

private fun prepareReanalyzeItems(episodeId: String) {
    val context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("nuvio_downloads_settings", Context.MODE_PRIVATE).edit().clear().commit()
    context.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE).edit().clear().commit()
    DownloadsSettingsStorage.initialize(context)
    DownloadsStorage.initialize(context)
    DownloadsPlatformDownloader.initialize(context)
    DownloadsSettingsRepository.clearLocalState()
    DownloadsRepository.clearLocalState()
    MediaAnalysisFiles.retainForTests = true
    MediaAnalysisFiles.clearForTests()
    MediaAnalysisStore.clearCacheForTests()
    MediaAnalysisScheduler.resetForTest()
    MediaAnalysisScheduler.supportedOverride = false
    DownloadsRepository.replaceItems(
        listOf(
            downloadItem(url = "fixture://media/s1e1", id = episodeId).copy(
                contentType = "series",
                parentMetaId = UiFixtureCatalog.DOWNLOADS_SEASON_SHOW_ID,
                parentMetaType = "series",
                videoId = "${UiFixtureCatalog.DOWNLOADS_SEASON_IMDB}:1:1",
                title = UiFixtureCatalog.LONG_TITLE,
                seasonNumber = 1,
                episodeNumber = 1,
                status = DownloadStatus.Completed,
            ),
        ),
    )
}

private fun ReanalyzeConfirmationFitTest.assertFits(
    width: Dp,
    height: Dp,
    safe: DisplaySafeInsets,
    label: String,
    compact: Boolean,
) {
    assertFits(compose, width, height, safe, label, compact)
}

private fun assertFits(
    compose: androidx.compose.ui.test.junit4.ComposeContentTestRule,
    width: Dp,
    height: Dp,
    safe: DisplaySafeInsets,
    label: String,
    compact: Boolean,
) {
    val question = "Re-analyze $label?"
    compose.onNodeWithText(question).assertExists()
    compose.onNodeWithTag(REANALYZE_EXPLANATION_TAG, useUnmergedTree = true)
        .performScrollTo()
        .assertIsDisplayed()
    val viewport = compose.onNodeWithTag("viewport").getBoundsInRoot()
    assertEquals(width, viewport.right - viewport.left)
    assertEquals(height, viewport.bottom - viewport.top)
    val card = compose.onNodeWithTag(REANALYZE_CONFIRMATION_TAG).getBoundsInRoot()
    val body = compose.onNodeWithTag(REANALYZE_BODY_TAG, useUnmergedTree = true).getBoundsInRoot()
    val safeLeft = viewport.left + safe.left
    val safeTop = viewport.top + safe.top
    val safeRight = viewport.right - safe.right
    val safeBottom = viewport.bottom - safe.bottom
    assertTrue(card.left >= safeLeft, "card left ${card.left}")
    assertTrue(card.top >= safeTop, "card top ${card.top}")
    assertTrue(card.right <= safeRight, "card right ${card.right}")
    assertTrue(card.bottom <= safeBottom, "card bottom ${card.bottom}")
    listOf(REANALYZE_CANCEL_TAG, REANALYZE_CONFIRM_TAG).forEach { tag ->
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot()
        val buttonHeight = bounds.bottom - bounds.top
        assertTrue(buttonHeight >= 44.dp, "$tag height $buttonHeight")
        assertTrue(bounds.left >= safeLeft, "$tag left")
        assertTrue(bounds.top >= safeTop, "$tag top")
        assertTrue(bounds.right <= safeRight, "$tag right")
        assertTrue(bounds.bottom <= safeBottom, "$tag bottom")
        assertTrue(bounds.top + 1.dp >= body.bottom, "$tag overlaps the title")
    }
    if (compact) {
        val safeHeight = safeBottom - safeTop
        val cardHeight = card.bottom - card.top
        assertTrue(cardHeight < safeHeight * 0.6f, "card height $cardHeight safe $safeHeight")
    }
}
