package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerChromeTest {
    @Test
    fun `reduce transparency replaces the fallback fill with an opaque scrim`() {
        assertEquals(PlayerChromeKind.FallbackFill, playerChromeKind(reduceTransparency = false))
        assertEquals(PlayerChromeKind.OpaqueScrim, playerChromeKind(reduceTransparency = true))
        assertEquals(1f, PlayerOpaqueScrim.alpha)
        assertEquals(Color(0xFF1C1C1E), PlayerOpaqueScrim)
    }

    @Test
    fun `side panel scrim stays light on iOS and opaque enough when transparency is reduced`() {
        assertEquals(0.34f, playerSidePanelScrimAlpha(ios = false, reduceTransparency = false))
        assertEquals(0.34f, playerSidePanelScrimAlpha(ios = false, reduceTransparency = true))
        assertEquals(0.16f, playerSidePanelScrimAlpha(ios = true, reduceTransparency = false))
        assertEquals(0.55f, playerSidePanelScrimAlpha(ios = true, reduceTransparency = true))
    }
}
