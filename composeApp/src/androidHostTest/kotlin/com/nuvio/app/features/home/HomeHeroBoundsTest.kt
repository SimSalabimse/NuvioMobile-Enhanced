package com.nuvio.app.features.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Portrait phone. The background fills the viewport. Hero content uses one top inset.
 * 59 is the Pro safe top. 20 is the small-phone top. Default text scale does not add another.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp")
class HomeHeroBoundsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun heroContentClearsThePortraitInsetOnce() {
        assertEquals(59f, homeContentTopInsetDp(0f, 59f))
        assertEquals(20f, homeContentTopInsetDp(0f, 20f))
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("home-background")) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = homeContentTopInsetDp(0f, HOME_PRO_PORTRAIT_TOP_DP).dp)
                        .testTag(HOME_HERO_CONTENT_TAG),
                ) {
                    Box(Modifier.fillMaxWidth().height(226.dp).testTag("home-hero-art"))
                    Text("Title", modifier = Modifier.testTag("home-hero-title"))
                }
            }
        }
        val background = compose.onNodeWithTag("home-background").getBoundsInRoot()
        val content = compose.onNodeWithTag(HOME_HERO_CONTENT_TAG).getBoundsInRoot()
        val title = compose.onNodeWithTag("home-hero-title").getBoundsInRoot()
        assertEquals(background.top, content.top - HOME_PRO_PORTRAIT_TOP_DP.dp)
        assertTrue(title.top >= content.top)
        assertTrue(content.top < (HOME_PRO_PORTRAIT_TOP_DP * 2).dp)
    }
}
