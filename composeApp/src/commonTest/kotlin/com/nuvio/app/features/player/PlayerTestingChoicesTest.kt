package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTestingChoicesTest {
    @Test
    fun defaultsMatchTheComparisonBuild() {
        assertEquals(TestingControlsStyle.ForkButtons, TestingControlsStyle.decode(null))
        assertEquals(TestingControlsStyle.ForkButtons, TestingControlsStyle.decode("nope"))
        assertEquals(TestingSyncUi.BottomStrip, TestingSyncUi.decode(null))
        assertEquals(TestingSyncUi.BottomStrip, TestingSyncUi.decode(""))
        assertEquals(TestingTitleWhileSpeed.Stays, TestingTitleWhileSpeed.decode(null))
    }

    @Test
    fun storedIdsRoundTrip() {
        assertEquals(TestingControlsStyle.PopupMenu, TestingControlsStyle.decode("popup_menu"))
        assertEquals(TestingControlsStyle.ForkButtons, TestingControlsStyle.decode("fork_buttons"))
        assertEquals(TestingSyncUi.TopCard, TestingSyncUi.decode("top_card"))
        assertEquals(TestingSyncUi.BottomStrip, TestingSyncUi.decode("bottom_strip"))
        assertEquals(TestingTitleWhileSpeed.Fades, TestingTitleWhileSpeed.decode("fades"))
        assertEquals(TestingTitleWhileSpeed.Stays, TestingTitleWhileSpeed.decode("stays"))
    }

    @Test
    fun titleFadesOnlyForTheOursChoice() {
        assertFalse(testingTitleHidden(TestingTitleWhileSpeed.Stays, speedOpen = true, menuOpen = true))
        assertFalse(testingTitleHidden(TestingTitleWhileSpeed.Fades, speedOpen = false, menuOpen = false))
        assertTrue(testingTitleHidden(TestingTitleWhileSpeed.Fades, speedOpen = true, menuOpen = false))
        assertTrue(testingTitleHidden(TestingTitleWhileSpeed.Fades, speedOpen = false, menuOpen = true))
    }
}
