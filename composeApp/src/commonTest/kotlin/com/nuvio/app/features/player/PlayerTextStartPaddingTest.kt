package com.nuvio.app.features.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerTextStartPaddingTest {
    @Test
    fun `zero inset keeps the flat padding`() {
        assertEquals(20.dp, playerTextStartPadding(flatPadding = 20.dp, safeInset = 0.dp))
    }

    @Test
    fun `inset replaces flat padding and stays clear of the island`() {
        val start = playerTextStartPadding(flatPadding = 20.dp, safeInset = 59.dp)
        assertEquals(52.dp, start)
        assertTrue(start < 20.dp + 59.dp)
        assertTrue(start >= PlayerTextIslandClearance)
    }

    @Test
    fun `trim does not move text under the island`() {
        assertEquals(52.dp, playerTextStartPadding(flatPadding = 20.dp, safeInset = 54.dp))
    }

    @Test
    fun `an inset inside the clearance is not pushed further out`() {
        assertEquals(40.dp, playerTextStartPadding(flatPadding = 20.dp, safeInset = 40.dp))
    }
}
