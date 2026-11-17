package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleSwatchMarkerTest {
    @Test
    fun lightSwatchesGetADarkMarkAndDarkSwatchesGetWhite() {
        assertEquals(Color(0xFF14161B), subtitleSwatchMarkerColor(Color.White))
        assertEquals(Color(0xFF14161B), subtitleSwatchMarkerColor(Color(0xFFFFD700)))
        assertEquals(Color.White, subtitleSwatchMarkerColor(Color.Black))
        assertEquals(Color.White, subtitleSwatchMarkerColor(Color(0xFF3B82F6)))
    }
}
