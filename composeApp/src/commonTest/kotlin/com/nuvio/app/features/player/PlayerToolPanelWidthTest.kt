package com.nuvio.app.features.player

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerToolPanelWidthTest {
    @Test
    fun `phones stay 360 and wide windows use 400 until the end inset runs out`() {
        assertEquals(360.dp, playerToolPanelWidth(874.dp, 0.dp, wide = false))
        assertEquals(400.dp, playerToolPanelWidth(1024.dp, 0.dp, wide = true))
        assertEquals(340.dp, playerToolPanelWidth(400.dp, 60.dp, wide = false))
        assertEquals(300.dp, playerToolPanelWidth(360.dp, 60.dp, wide = true))
        assertEquals(0.dp, playerToolPanelWidth(40.dp, 60.dp, wide = true))
        assertFalse(playerToolPanelIsWide(599.dp))
        assertTrue(playerToolPanelIsWide(600.dp))
        assertFalse(playerToolPanelIsWide(390.dp))
    }

    @Test
    fun `system back returns to the sub page and a locked send ignores back and close`() {
        assertEquals(PlayerToolBackAction.Back, playerToolPanelBackAction(dismissLocked = false, hasBack = true))
        assertEquals(PlayerToolBackAction.Dismiss, playerToolPanelBackAction(dismissLocked = false, hasBack = false))
        assertEquals(PlayerToolBackAction.Ignore, playerToolPanelBackAction(dismissLocked = true, hasBack = true))
        assertEquals(PlayerToolBackAction.Ignore, playerToolPanelBackAction(dismissLocked = true, hasBack = false))
    }

    @Test
    fun `the video beside a tool is dimmed 30 percent unless iOS reduces transparency`() {
        assertEquals(0.30f, playerToolPanelScrimAlpha(ios = true, reduceTransparency = false))
        assertEquals(0.30f, playerToolPanelScrimAlpha(ios = false, reduceTransparency = false))
        assertEquals(0.30f, playerToolPanelScrimAlpha(ios = false, reduceTransparency = true))
        assertEquals(0.55f, playerToolPanelScrimAlpha(ios = true, reduceTransparency = true))
    }
}
