package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DETAIL_OVERVIEW_TAG
import com.nuvio.app.features.details.components.DetailOverviewSection
import com.nuvio.app.features.player.skip.MOVIE_SEGMENT_STATUS_TAG
import com.nuvio.app.features.player.skip.MovieSegmentStatusList
import com.nuvio.app.features.player.skip.PublicMediaStatus
import com.nuvio.app.features.player.skip.movieSegmentFixtureRows
import com.nuvio.app.features.player.skip.movieSegmentRows
import com.nuvio.app.features.player.skip.movieSegmentStatusAttached
import com.nuvio.app.features.player.skip.MovieSegmentSurface
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iPhone 16 Pro logical size. The catalogue is the next block in the overview column.
 * An empty catalogue is one line. A show does not draw the movie block.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp-mdpi")
class DetailCatalogueFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anEmptyMovieCatalogueIsOneLineUnderTheOverview() {
        compose.setContent {
            overview(movie(), fontScale = 1f, populated = false)
        }
        compose.waitForIdle()
        val status = compose.onNodeWithTag(MOVIE_SEGMENT_STATUS_TAG).assertIsDisplayed().getBoundsInRoot()
        val overview = compose.onNodeWithText("synthetic overview", substring = true).getBoundsInRoot()
        assertTrue(status.top >= overview.bottom - 1.dp)
        assertFalse(overlaps(status, yearBounds()))
        assertFalse(overlaps(status, labeled("Director:  ")))
        assertFalse(overlaps(status, labeled("Writer:  ")))
        assertFalse(overlaps(status, overview))
        compose.onNodeWithText("No public Intro").assertDoesNotExist()
        compose.onNodeWithText("No public Recap").assertDoesNotExist()
        compose.onNodeWithText("No public Credits").assertDoesNotExist()
        compose.onNodeWithText("No public Previews").assertDoesNotExist()
        assertTrue(status.left >= 0.dp)
        assertTrue(status.right <= 402.dp + 1.dp)
    }

    @Test
    fun populatedRowsStayUnderTheMetadataAt16ProWidth() {
        compose.setContent {
            overview(movie(), fontScale = 1f, populated = true)
        }
        compose.waitForIdle()
        assertCatalogueClearsTheMetadata()
    }

    @Test
    fun aLargeTypeSizeKeepsTheCatalogueInTheColumn() {
        compose.setContent {
            overview(movie(), fontScale = 2.5f, populated = true)
        }
        compose.waitForIdle()
        assertCatalogueClearsTheMetadata()
        val status = compose.onNodeWithTag(MOVIE_SEGMENT_STATUS_TAG).getBoundsInRoot()
        assertTrue(status.left >= 0.dp)
        assertTrue(status.right <= 402.dp + 1.dp)
    }

    @Test
    fun aLargeTypeSizeCollapsesAnEmptyCatalogueToOneWrappingLine() {
        compose.setContent {
            overview(movie(), fontScale = 2.5f, populated = false)
        }
        compose.waitForIdle()
        val status = compose.onNodeWithTag(MOVIE_SEGMENT_STATUS_TAG).assertIsDisplayed().getBoundsInRoot()
        val overview = compose.onNodeWithText("synthetic overview", substring = true).getBoundsInRoot()
        assertTrue(status.top >= overview.bottom - 1.dp)
        assertFalse(overlaps(status, overview))
        compose.onNodeWithText("No public Intro").assertDoesNotExist()
        assertTrue(status.right <= 402.dp + 1.dp)
    }

    @Test
    fun aShowDoesNotDrawTheMovieCatalogueOverItsMetadata() {
        compose.setContent {
            overview(movie().copy(type = "series"), fontScale = 1f, populated = true)
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DETAIL_OVERVIEW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOVIE_SEGMENT_STATUS_TAG).assertDoesNotExist()
        compose.onNodeWithText("No public submissions").assertDoesNotExist()
        compose.onNodeWithText("synthetic overview", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Director:  ").assertIsDisplayed()
    }

    private fun assertCatalogueClearsTheMetadata() {
        val status = compose.onNodeWithTag(MOVIE_SEGMENT_STATUS_TAG).assertIsDisplayed().getBoundsInRoot()
        val overview = compose.onNodeWithText("synthetic overview", substring = true).getBoundsInRoot()
        assertTrue(status.top >= overview.bottom - 1.dp)
        assertFalse(overlaps(status, yearBounds()))
        assertFalse(overlaps(status, labeled("Director:  ")))
        assertFalse(overlaps(status, labeled("Writer:  ")))
        assertFalse(overlaps(status, overview))
    }

    private fun yearBounds() = compose.onNodeWithText("1994").getBoundsInRoot()

    private fun labeled(label: String) = compose.onNodeWithText(label, substring = true).getBoundsInRoot()

    private fun overlaps(a: DpRect, b: DpRect): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    @Composable
    private fun overview(meta: MetaDetails, fontScale: Float, populated: Boolean) {
        CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
            NuvioTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.width(402.dp)) {
                        DetailOverviewSection(meta = meta) {
                            if (movieSegmentStatusAttached(MovieSegmentSurface.Details, meta.type == "movie")) {
                                MovieSegmentStatusList(
                                    rows = if (populated) {
                                        movieSegmentFixtureRows()
                                    } else {
                                        movieSegmentRows(
                                            media = null,
                                            publicStatus = PublicMediaStatus.NoSubmissions,
                                            confirmed = emptySet(),
                                            pending = emptySet(),
                                            detected = emptySet(),
                                        )
                                    },
                                    publicStatus = if (populated) {
                                        PublicMediaStatus.Found
                                    } else {
                                        PublicMediaStatus.NoSubmissions
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun movie() = MetaDetails(
        id = "tt0000001",
        type = "movie",
        name = "Northwind",
        description = "A synthetic overview that must stay clear of the catalogue.",
        releaseInfo = "1994",
        runtime = "103",
        ageRating = "PG",
        director = listOf("A. Director"),
        writer = listOf("A. Writer"),
    )
}
