package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalDetailChromeTopOverride
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.DETAIL_HERO_ART_TAG
import com.nuvio.app.features.details.components.DETAIL_HERO_FADE_TAG
import com.nuvio.app.features.details.components.DETAIL_HERO_HEADING_TAG
import com.nuvio.app.features.details.components.DetailHero
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Phone portrait. The backdrop fills the hero through the logo. A 16:9 strip
 * would end on a hard cut and leave the title on the flat page color.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp-mdpi")
class DetailHeroBleedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theLogoSitsOnThePictureAndTheFadeCoversTheCut() {
        compose.setContent {
            NuvioTheme {
                CompositionLocalProvider(LocalDetailChromeTopOverride provides 59.dp) {
                    DetailHero(
                        meta = MetaDetails(
                            id = "fixture-1",
                            type = "series",
                            name = "Northwind",
                            genres = listOf("Drama", "Mystery"),
                        ),
                        portraitPhone = true,
                    )
                }
            }
        }
        compose.waitForIdle()
        val art = compose.onNodeWithTag(DETAIL_HERO_ART_TAG).getBoundsInRoot()
        val heading = compose.onNodeWithTag(DETAIL_HERO_HEADING_TAG).getBoundsInRoot()
        val fade = compose.onNodeWithTag(DETAIL_HERO_FADE_TAG).getBoundsInRoot()
        val band = 402.dp * 9f / 16f
        assertTrue(art.bottom - art.top > band + 100.dp)
        assertTrue(heading.top > art.top)
        assertTrue(heading.bottom <= art.bottom + 1.dp)
        assertTrue(heading.bottom > art.top + band)
        assertTrue(heading.top + 1.dp >= art.top + band)
        assertNear(fade.bottom, art.bottom)
        assertTrue(fade.top < heading.top)
        assertTrue(fade.top < art.top + band)
        assertTrue(fade.bottom - fade.top > 36.dp)
    }

    private fun assertNear(actual: Dp, expected: Dp) {
        assertTrue(actual >= expected - 1.dp && actual <= expected + 1.dp)
    }
}
