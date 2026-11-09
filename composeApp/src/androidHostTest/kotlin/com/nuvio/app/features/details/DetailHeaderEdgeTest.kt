package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalDetailChromeTopOverride
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DETAIL_HEADER_ACTION_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_BACK_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_EDGE_TAG
import com.nuvio.app.features.details.components.DETAIL_HEADER_TITLE_TAG
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.navigation.LocalNativeNavigationBarHidden
import com.nuvio.app.navigation.LocalUseNativeNavigation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One header. The back control and the library action share the row that clears the
 * live inset. The title joins that row. It is not a second strip. The fade continues
 * past the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp-mdpi")
class DetailHeaderEdgeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBackAndActionStayInOneRowAndTheFadeContinuesPastIt() {
        val safeTop = 59.dp
        var saves = 0
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(
                    LocalDetailChromeTopOverride provides safeTop,
                    LocalUseNativeNavigation provides true,
                    LocalNativeNavigationBarHidden provides true,
                ) {
                    DetailFloatingHeader(
                        meta = MetaDetails(id = "fixture-1", type = "series", name = "Northwind"),
                        isSaved = false,
                        progress = 0f,
                        onBack = {},
                        onToggleSaved = { saves += 1 },
                    )
                }
            }
        }
        compose.waitForIdle()
        val back = compose.onNodeWithTag(DETAIL_HEADER_BACK_TAG).getBoundsInRoot()
        val action = compose.onNodeWithTag(DETAIL_HEADER_ACTION_TAG).getBoundsInRoot()
        val edge = compose.onNodeWithTag(DETAIL_HEADER_EDGE_TAG).getBoundsInRoot()
        compose.onNodeWithTag(DETAIL_HEADER_TITLE_TAG).assertDoesNotExist()
        assertTrue(back.top >= safeTop)
        assertTrue(back.bottom - back.top >= 44.dp)
        assertTrue(action.bottom - action.top >= 44.dp)
        assertEquals(back.top, action.top)
        assertTrue(edge.bottom > back.bottom)
        assertTrue(edge.bottom - edge.top > safeTop + 56.dp)
        assertTrue(edge.bottom - edge.top <= safeTop + 56.dp + 28.dp)
        compose.onNodeWithTag(DETAIL_HEADER_ACTION_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, saves)
    }

    @Test
    fun theTitleJoinsTheSameRowInsteadOfASecondStrip() {
        val safeTop = 59.dp
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(
                    LocalDetailChromeTopOverride provides safeTop,
                    LocalUseNativeNavigation provides true,
                    LocalNativeNavigationBarHidden provides true,
                ) {
                    DetailFloatingHeader(
                        meta = MetaDetails(id = "fixture-1", type = "series", name = "Northwind"),
                        isSaved = true,
                        progress = 1f,
                        onBack = {},
                        onToggleSaved = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val back = compose.onNodeWithTag(DETAIL_HEADER_BACK_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag(DETAIL_HEADER_TITLE_TAG).assertIsDisplayed().getBoundsInRoot()
        assertTrue(title.top < back.bottom)
        assertTrue(title.bottom > back.top)
        assertTrue(title.top >= safeTop)
    }
}
