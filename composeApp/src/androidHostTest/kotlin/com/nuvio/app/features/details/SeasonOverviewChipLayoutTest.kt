package com.nuvio.app.features.details

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.components.SeasonOverviewChipFilledKey
import com.nuvio.app.features.details.components.SeasonOverviewChipLineLimitKey
import com.nuvio.app.features.details.components.SeasonOverviewSegmentChips
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four segment words stay one line, and Missing is a chip of the same height.
 * A narrow row is the case that used to wrap Intro and Recap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w402dp-h874dp-mdpi")
class SeasonOverviewChipLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fourChipsStayOneLineAndMissingIsFilled() {
        val symbols = listOf(
            symbol(SeasonOverviewSymbolKind.Intro, SeasonSegmentPresence.Yours, "Intro: yours"),
            symbol(SeasonOverviewSymbolKind.Recap, SeasonSegmentPresence.Yours, "Recap: yours"),
            symbol(SeasonOverviewSymbolKind.Credits, SeasonSegmentPresence.Public, "Credits: public"),
            symbol(SeasonOverviewSymbolKind.Preview, SeasonSegmentPresence.Missing, "Preview: missing"),
        )
        compose.setContent {
            NuvioTheme {
                Box(Modifier.width(72.dp)) {
                    SeasonOverviewSegmentChips(symbols)
                }
            }
        }
        val heights = symbols.map { chip ->
            val tag = "season-overview-segment-${chip.kind.name}"
            val semantics = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().single()
            assertEquals(1, semantics.config[SeasonOverviewChipLineLimitKey])
            assertEquals(true, semantics.config[SeasonOverviewChipFilledKey])
            assertEquals(listOf(chip.accessibility), semantics.config[SemanticsProperties.ContentDescription])
            assertFalse(chip.accessibility.contains('\n'))
            val bounds = compose.onNodeWithTag(tag).getBoundsInRoot()
            bounds.bottom - bounds.top
        }
        heights.forEach { height ->
            assertTrue(height < 40.dp, "chip wrapped: $height")
        }
        heights.drop(1).forEach { height ->
            val difference = height - heights.first()
            assertTrue(difference < 1.dp && difference > (-1).dp, "chip heights differ: $heights")
        }
    }

    private fun symbol(
        kind: SeasonOverviewSymbolKind,
        presence: SeasonSegmentPresence,
        label: String,
    ) = SeasonOverviewSymbolUi(
        kind = kind,
        presence = presence,
        token = "${kind.name}:$presence",
        accessibility = label,
    )
}
