package com.nuvio.app.features.downloads

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.analysis.MediaAnalysisFiles
import com.nuvio.app.features.analysis.MediaAnalysisScheduler
import com.nuvio.app.features.analysis.MediaAnalysisStatus
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.player.FullscreenPlayerDialog
import com.nuvio.app.features.player.LockPlayerToLandscape
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
 * The re-analyze confirmation stays inside the layout that is on screen after the real player
 * dialog has been shown in landscape and dismissed back to portrait. Cancel does not start analysis.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w1000dp-h1000dp")
class ReanalyzeConfirmationPlacementTest {
    @get:Rule
    val compose = createComposeRule()

    private val episodeId = "s3e13"
    private val episodeLabel = "Show S3E13"

    @Before
    fun initialize() {
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
                downloadItem(url = "fixture://media/s3e13", id = episodeId).copy(
                    contentType = "series",
                    parentMetaId = "show",
                    parentMetaType = "series",
                    videoId = "show:3:13",
                    title = "Show",
                    seasonNumber = 3,
                    episodeNumber = 13,
                    status = DownloadStatus.Completed,
                ),
            ),
        )
    }

    @After
    fun clear() {
        MediaAnalysisScheduler.resetForTest()
        DownloadsRepository.clearLocalState()
    }

    @Test
    fun confirmationStaysInsideTheViewportAfterLandscapePlayerReturn() {
        var width by mutableStateOf(390.dp)
        var height by mutableStateOf(844.dp)
        var player by mutableStateOf(false)
        var target by mutableStateOf<ReanalyzeTarget?>(null)
        var dismissed by mutableIntStateOf(0)
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(width, height).testTag("viewport")) {
                        LazyColumn(Modifier.fillMaxSize().testTag("episode-list")) {
                            items(40) { index ->
                                Text(
                                    text = "Episode $index",
                                    modifier = Modifier
                                        .height(72.dp)
                                        .testTag("episode-row-$index"),
                                )
                            }
                        }
                        if (player) {
                            LockPlayerToLandscape()
                            FullscreenPlayerDialog(onDismiss = { player = false }) {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                        ReanalyzeModal(
                            target = target,
                            onDismiss = {
                                dismissed += 1
                                target = null
                            },
                        )
                    }
                }
            }
        }

        target = ReanalyzeTarget(episodeLabel, listOf(episodeId))
        compose.waitForIdle()
        assertConfirmationInside(width, height)
        val revisionBeforeCancel = MediaAnalysisStatus.revision.value
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(revisionBeforeCancel, MediaAnalysisStatus.revision.value)
        compose.onAllNodesWithTag(REANALYZE_CONFIRMATION_TAG).assertCountEquals(0)

        compose.onNodeWithTag("episode-list").performScrollToNode(hasTestTag("episode-row-30"))
        compose.waitForIdle()
        width = 844.dp
        height = 390.dp
        player = true
        compose.waitForIdle()
        player = false
        width = 390.dp
        height = 844.dp
        target = ReanalyzeTarget(episodeLabel, listOf(episodeId))
        compose.waitForIdle()
        assertConfirmationInside(390.dp, 844.dp)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(revisionBeforeCancel, MediaAnalysisStatus.revision.value)

        target = ReanalyzeTarget(episodeLabel, listOf(episodeId))
        compose.waitForIdle()
        assertConfirmationInside(390.dp, 844.dp)
        compose.onNodeWithText("Re-analyze").performClick()
        compose.waitForIdle()
        assertEquals(3, dismissed)
        assertTrue(MediaAnalysisStatus.revision.value > revisionBeforeCancel)
    }

    private fun assertConfirmationInside(width: Dp, height: Dp) {
        compose.onNodeWithText("Re-analyze $episodeLabel?").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("viewport").getBoundsInRoot()
        assertEquals(width, viewport.right - viewport.left)
        assertEquals(height, viewport.bottom - viewport.top)
        val spanX = viewport.right - viewport.left
        val spanY = viewport.bottom - viewport.top
        val viewMidX = viewport.left + spanX / 2
        val viewMidY = viewport.top + spanY / 2
        listOf(
            compose.onNodeWithTag(REANALYZE_CONFIRMATION_TAG),
            compose.onNodeWithText("Cancel"),
            compose.onNodeWithText("Re-analyze"),
        ).forEach { node ->
            val bounds = node.getBoundsInRoot()
            assertTrue(bounds.left >= viewport.left, "left ${bounds.left} viewport ${viewport.left}")
            assertTrue(bounds.top >= viewport.top, "top ${bounds.top}")
            assertTrue(bounds.right <= viewport.right, "right ${bounds.right} viewport ${viewport.right}")
            assertTrue(bounds.bottom <= viewport.bottom, "bottom ${bounds.bottom} viewport ${viewport.bottom}")
        }
        val card = compose.onNodeWithTag(REANALYZE_CONFIRMATION_TAG).getBoundsInRoot()
        val cardMidX = card.left + (card.right - card.left) / 2
        val cardMidY = card.top + (card.bottom - card.top) / 2
        val dx = if (cardMidX > viewMidX) cardMidX - viewMidX else viewMidX - cardMidX
        val dy = if (cardMidY > viewMidY) cardMidY - viewMidY else viewMidY - cardMidY
        assertTrue(dx <= spanX * 0.15f, "card mid x $cardMidX viewport mid $viewMidX")
        assertTrue(dy <= spanY * 0.15f, "card mid y $cardMidY viewport mid $viewMidY")
    }
}
