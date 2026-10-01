package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlagSheetDetentTest {
    @Test
    fun `flag sheet stays short of a tall phone and a landscape phone`() {
        assertEquals(640.0, flagSheetDetentHeight(900.0))
        val landscape = flagSheetDetentHeight(390.0)
        assertTrue(landscape < 390.0)
        assertEquals(319.8, landscape, absoluteTolerance = 0.01)
    }

    @Test
    fun `a very short height can use the whole sheet so the form still fits`() {
        assertEquals(250.0, flagSheetDetentHeight(250.0))
        assertEquals(0.0, flagSheetDetentHeight(0.0))
    }
}
