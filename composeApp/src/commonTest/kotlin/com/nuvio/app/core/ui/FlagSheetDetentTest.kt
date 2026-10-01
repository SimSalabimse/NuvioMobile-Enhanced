package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlagSheetDetentTest {
    @Test
    fun `flag sheet stays short of a tall phone and a landscape phone`() {
        assertEquals(340.0, flagSheetDetentHeight(900.0))
        val landscape = flagSheetDetentHeight(390.0)
        assertTrue(landscape < 390.0 - 72.0)
        assertEquals(280.8, landscape, absoluteTolerance = 0.01)
    }

    @Test
    fun `a very short height can use the whole sheet so the form still fits`() {
        assertEquals(250.0, flagSheetDetentHeight(250.0))
        assertEquals(0.0, flagSheetDetentHeight(0.0))
    }

    @Test
    fun `landscape card is narrower than the phone and floats`() {
        assertEquals(560.0, flagSheetCardWidth(874.0))
        assertEquals(402.0, flagSheetCardWidth(402.0))
        assertEquals(FLAG_SHEET_CARD_WIDTH, flagSheetCardWidth(0.0))
    }
}
