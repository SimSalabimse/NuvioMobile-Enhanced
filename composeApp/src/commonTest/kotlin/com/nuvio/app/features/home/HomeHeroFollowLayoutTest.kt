package com.nuvio.app.features.home

import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.features.home.components.homeHeroLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phone portrait hero is the 16:9 band (about 219dp on a 390dp phone), not a reserved
 * 1.5x slot. The viewport is the long side (844dp), so a row still fits under the artwork.
 */
class HomeHeroFollowLayoutTest {
    @Test
    fun firstRowFollowsTheHeroWithinSectionSpacing() {
        val viewportHeightDp = 844f
        val heroContentHeightDp = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = viewportHeightDp,
        ).heroHeight.value
        val sectionSpacingDp = NuvioTokens.Space.s12.value
        assertEquals(HomeSectionSpacingDp, sectionSpacingDp, 0.01f)
        assertTrue(
            heroContentHeightDp + sectionSpacingDp < viewportHeightDp,
            "artwork $heroContentHeightDp plus spacing should leave a row on a $viewportHeightDp screen",
        )

        HomeHeroFollowPhase.entries.forEach { phase ->
            val state = homeHeroFollowStateFor(
                phase = phase,
                viewportHeightDp = viewportHeightDp,
                heroContentHeightDp = heroContentHeightDp,
                sectionSpacingDp = sectionSpacingDp,
            )
            val layout = homeHeroFollowLayout(state)
            val artworkSlot = state.heroContentHeightDp * state.heroReveal.coerceIn(0f, 1f)
            assertEquals(
                artworkSlot,
                layout.heroSlotHeightDp,
                0.01f,
                "${phase.name} slot follows the artwork, not the ${state.viewportHeightDp}dp viewport",
            )
            assertEquals(0f, layout.contentTopDp, 0.01f, phase.name)
            assertEquals(0f, layout.spacerBelowHeroDp, 0.01f, phase.name)
            assertTrue(
                layout.gapDp <= state.sectionSpacingDp + 0.01f,
                "${phase.name} gap ${layout.gapDp} between hero bottom ${layout.heroBottomDp} " +
                    "and first row ${layout.firstRowTopDp} is larger than section spacing " +
                    state.sectionSpacingDp,
            )
            assertEquals(state.rowsLoaded, layout.rowsVisible, phase.name)
        }
    }
}
