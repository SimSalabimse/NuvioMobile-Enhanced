package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `menu body alphas stay see-through unless reduce transparency is on`() {
        assertEquals(0.42f, PlayerMenuFallbackAlpha)
        assertEquals(0.28f, PlayerMenuMaterialTintAlpha)
        assertEquals(0.28f, PlayerCenteredCardVeilAlpha)
        assertEquals(PlayerCenteredCardVeilAlpha, PlayerCenteredCardVeil.alpha, 0.01f)
        assertEquals(0.08f, PlayerMenuRowAlpha)
        assertEquals(PlayerMenuRowAlpha, PlayerMenuRowFill.alpha, 0.01f)
        assertEquals(0.12f, PlayerMenuHeaderPillAlpha)
        assertEquals(PlayerMenuHeaderPillAlpha, PlayerMenuHeaderPillFill.alpha, 0.01f)
        assertEquals(0.45f, PlayerMenuSelectedAccentAlpha)

        assertEquals(1f, playerMenuBodyAlpha(reduceTransparency = true, materialSamplesPicture = false))
        assertEquals(1f, playerMenuBodyAlpha(reduceTransparency = true, materialSamplesPicture = true))
        assertEquals(
            PlayerMenuFallbackAlpha,
            playerMenuBodyAlpha(reduceTransparency = false, materialSamplesPicture = false),
        )
        assertEquals(
            PlayerMenuMaterialTintAlpha,
            playerMenuBodyAlpha(reduceTransparency = false, materialSamplesPicture = true),
        )

        assertFalse(playerMaterialSamplesPicture(ios = false, reduceTransparency = false))
        assertFalse(playerMaterialSamplesPicture(ios = true, reduceTransparency = true))
        assertTrue(playerMaterialSamplesPicture(ios = true, reduceTransparency = false))

        assertFalse(playerMenuUsesSystemMaterial(reduceTransparency = true, materialSamplesPicture = true))
        assertFalse(playerMenuUsesSystemMaterial(reduceTransparency = false, materialSamplesPicture = false))
        assertTrue(playerMenuUsesSystemMaterial(reduceTransparency = false, materialSamplesPicture = true))

        val opaqueAccent = Color(0xFF5B8CFF)
        assertEquals(PlayerMenuSelectedAccentAlpha, playerMenuSelectedColor(opaqueAccent).alpha, 0.01f)
        assertEquals(opaqueAccent.red, playerMenuSelectedColor(opaqueAccent).red, 0.01f)
        val translucentAccent = opaqueAccent.copy(alpha = 0.2f)
        assertEquals(translucentAccent.alpha, playerMenuSelectedColor(translucentAccent).alpha, 0.001f)
    }
}
