package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalDetailChromeTopOverride
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DETAIL_HEADER_COVER_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_ROW_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_TAG
import com.nuvio.app.features.details.components.DETAIL_HERO_HEADING_TAG
import com.nuvio.app.features.details.components.DETAIL_PLAY_TAG
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.features.details.components.DetailHeaderCoverAlphaKey
import com.nuvio.app.features.details.components.DetailHeaderCoverFade
import com.nuvio.app.features.details.components.DetailHeaderOcclusion
import com.nuvio.app.features.details.components.DetailHeaderOcclusionTarget
import com.nuvio.app.features.details.components.LocalDetailHeaderOcclusion
import com.nuvio.app.features.details.components.rememberDetailHeaderCoverProgress
import com.nuvio.app.features.details.components.reportDetailHeaderListOrigin
import com.nuvio.app.features.details.components.reportDetailHeaderOcclusion
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The title sits on the 16:9 band, the way a wide phone backdrop places the hero heading.
 * The cover is driven by that top against the header row, on SE and 16 Pro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "mdpi")
class DetailHeaderCoverThresholdTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w375dp-h667dp-mdpi")
    fun seCoverIsClearAtRestAndSolidWhenTheTitleMeetsTheRow() {
        assertThreshold(width = 375.dp, height = 667.dp, safeTop = 20.dp, reduceMotion = false)
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-mdpi")
    fun proCoverIsClearAtRestAndSolidWhenTheTitleMeetsTheRow() {
        assertThreshold(width = 402.dp, height = 874.dp, safeTop = 59.dp, reduceMotion = false)
    }

    @Test
    @Config(qualifiers = "w375dp-h667dp-mdpi")
    fun seReduceMotionSnapsWhenTheTitleMeetsTheRow() {
        assertThreshold(width = 375.dp, height = 667.dp, safeTop = 20.dp, reduceMotion = true)
    }

    @Test
    @Config(qualifiers = "w402dp-h874dp-mdpi")
    fun proReduceMotionSnapsWhenTheTitleMeetsTheRow() {
        assertThreshold(width = 402.dp, height = 874.dp, safeTop = 59.dp, reduceMotion = true)
    }

    private fun assertThreshold(width: Dp, height: Dp, safeTop: Dp, reduceMotion: Boolean) {
        val listState = LazyListState()
        compose.setContent {
            val occlusion = remember(listState) {
                DetailHeaderOcclusion(
                    scrollOffsetPx = {
                        if (listState.firstVisibleItemIndex == 0) {
                            listState.firstVisibleItemScrollOffset.toFloat()
                        } else {
                            Float.POSITIVE_INFINITY
                        }
                    },
                    heroScrolledOff = { listState.firstVisibleItemIndex > 0 },
                )
            }
            NuvioTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 1f, fontScale = 1f),
                    LocalDetailChromeTopOverride provides safeTop,
                    LocalDetailHeaderOcclusion provides occlusion,
                    LocalUseNativeNavigation provides true,
                    LocalNativeNavigationBarHidden provides true,
                ) {
                    val progress = rememberDetailHeaderCoverProgress(reduceMotion = reduceMotion)
                    Box(Modifier.requiredSize(width, height)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .reportDetailHeaderListOrigin(),
                        ) {
                            item {
                                Spacer(Modifier.height(width * 9f / 16f))
                                Box(
                                    Modifier
                                        .reportDetailHeaderOcclusion(DetailHeaderOcclusionTarget.Heading)
                                        .testTag(DETAIL_HERO_HEADING_TAG)
                                        .fillMaxWidth()
                                        .height(72.dp)
                                        .background(Color.White),
                                )
                                Spacer(Modifier.height(24.dp))
                                Box(
                                    Modifier
                                        .reportDetailHeaderOcclusion(DetailHeaderOcclusionTarget.Play)
                                        .testTag(DETAIL_PLAY_TAG)
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .background(Color.Red),
                                )
                                Spacer(Modifier.height(height))
                            }
                        }
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
        }
        compose.waitForIdle()

        assertEquals(0f, coverAlpha())
        compose.onNodeWithTag(DETAIL_HEADER_COVER_TAG).assertDoesNotExist()
        val titleAtRest = compose.onNodeWithTag(DETAIL_HERO_HEADING_TAG).getBoundsInRoot()
        val rowAtRest = compose.onNodeWithTag(DETAIL_HEADER_ROW_TAG).getBoundsInRoot()
        val gap = titleAtRest.top - rowAtRest.bottom
        assertTrue(gap > DetailHeaderCoverFade + 8.dp)

        val fadePx = with(compose.density) { DetailHeaderCoverFade.toPx() }
        val earlyPx = floor(with(compose.density) { gap.toPx() } - fadePx - 4f).toInt()
        assertTrue(earlyPx > 0)
        scrollTo(listState, earlyPx)
        assertEquals(0f, coverAlpha())
        val titleEarly = compose.onNodeWithTag(DETAIL_HERO_HEADING_TAG).getBoundsInRoot()
        val rowEarly = compose.onNodeWithTag(DETAIL_HEADER_ROW_TAG).getBoundsInRoot()
        assertTrue(titleEarly.top > rowEarly.bottom + DetailHeaderCoverFade)

        val meetPx = ceil(with(compose.density) { gap.toPx() }.toDouble()).toInt()
        scrollTo(listState, meetPx)
        val titleMet = compose.onNodeWithTag(DETAIL_HERO_HEADING_TAG).getBoundsInRoot()
        val rowMet = compose.onNodeWithTag(DETAIL_HEADER_ROW_TAG).getBoundsInRoot()
        val playMet = compose.onNodeWithTag(DETAIL_PLAY_TAG).getBoundsInRoot()
        assertTrue(titleMet.top <= rowMet.bottom + 1.dp)
        assertTrue(titleMet.top >= rowMet.bottom - 1.dp)
        assertTrue(playMet.top > rowMet.bottom + 24.dp)
        assertTrue(coverAlpha() >= 0.95f)
        val cover = compose.onNodeWithTag(DETAIL_HEADER_COVER_TAG).getBoundsInRoot()
        assertEquals(0.dp, cover.top)
        assertTrue(cover.bottom >= rowMet.bottom)
    }

    private fun scrollTo(listState: LazyListState, offsetPx: Int) {
        compose.runOnIdle { listState.requestScrollToItem(0, offsetPx) }
        compose.waitForIdle()
    }

    private fun coverAlpha(): Float {
        return compose.onNodeWithTag(DETAIL_HEADER_TAG)
            .fetchSemanticsNode()
            .config[DetailHeaderCoverAlphaKey]
    }
}
