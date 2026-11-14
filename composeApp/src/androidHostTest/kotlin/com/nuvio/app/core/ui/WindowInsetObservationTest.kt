package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * The first composition can run before the view has a window.
 * The inset has to move once that window attaches. A one-shot read stays at 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp")
class WindowInsetObservationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTopInsetUpdatesWhenTheWindowAttachesAfterTheFirstComposition() {
        var attached = false
        var listener: (() -> Unit)? = null
        compose.setContent {
            val insets = rememberLatestInset(
                key = Unit,
                read = { DisplaySafeInsets(top = if (attached) 54.dp else 0.dp) },
                subscribe = { onChange ->
                    listener = onChange
                    { listener = null }
                },
            )
            Box(Modifier.fillMaxSize().testTag("window")) {
                Box(
                    Modifier
                        .padding(top = insets.top)
                        .fillMaxSize()
                        .testTag("content"),
                )
            }
        }
        compose.waitForIdle()
        val window = compose.onNodeWithTag("window").getBoundsInRoot()
        val before = compose.onNodeWithTag("content").getBoundsInRoot()
        assertEquals(window.top, before.top)
        attached = true
        val publish = listener
        assertTrue(publish != null, "the inset reader never subscribed to a later window")
        publish.invoke()
        compose.waitForIdle()
        val after = compose.onNodeWithTag("content").getBoundsInRoot()
        assertEquals(window.top + 54.dp, after.top)
    }
}
