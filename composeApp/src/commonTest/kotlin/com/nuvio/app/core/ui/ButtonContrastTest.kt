package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class ButtonContrastTest {
    @Test
    fun disabledFilledButtonsClearTextContrastOnEveryTheme() {
        AppTheme.entries.forEach { theme ->
            val palette = ThemeColors.getColorPalette(theme)
            val filled = disabledFilledButtonColors(
                fill = palette.secondary,
                onFill = palette.onSecondary,
                backdrop = palette.backgroundCard,
            )
            val before = legacyFadedLabel(
                fill = palette.secondary,
                onFill = palette.onSecondary,
                backdrop = palette.backgroundCard,
            )
            val after = contrastRatio(filled.content, filled.container)
            assertTrue(
                after >= TEXT_CONTRAST_MIN,
                "${theme.name} filled label $after is under $TEXT_CONTRAST_MIN " +
                    "(before ${contrastRatio(before.content, before.container)})",
            )
            assertTrue(
                filled.container != palette.secondary,
                "${theme.name} disabled fill matches the enabled accent",
            )
            val ghost = disabledGhostButtonColors(
                fill = Color(0xFFF5F7F8).copy(alpha = NuvioTokens.Opacity.hover),
                content = Color(0xFFF5F7F8),
                backdrop = palette.backgroundElevated,
            )
            val ghostRatio = contrastRatio(ghost.content, ghost.container)
            assertTrue(
                ghostRatio >= TEXT_CONTRAST_MIN,
                "${theme.name} ghost label $ghostRatio is under $TEXT_CONTRAST_MIN",
            )
            assertTrue(
                ghost.content.luminance() < Color(0xFFF5F7F8).luminance(),
                "${theme.name} ghost label is no dimmer than the enabled label",
            )
        }
    }

    @Test
    fun offSwitchTrackClearsNonTextContrastOnEveryCard() {
        val track = uncheckedSwitchTrackColor(Color(0xFFB8BEC5), Color(0xFF252A2A))
        val thumb = Color(0xFFF5F7F8)
        assertTrue(contrastRatio(thumb, track) >= NON_TEXT_CONTRAST_MIN)
        AppTheme.entries.forEach { theme ->
            val card = ThemeColors.getColorPalette(theme).backgroundCard
            val before = contrastRatio(Color(0xFF252A2A), card)
            val after = contrastRatio(track, card)
            assertTrue(before < NON_TEXT_CONTRAST_MIN, "${theme.name} old track unexpectedly passed")
            assertTrue(after >= NON_TEXT_CONTRAST_MIN, "${theme.name} off track $after is under 3:1")
        }
    }

    private fun legacyFadedLabel(fill: Color, onFill: Color, backdrop: Color): ResolvedButtonColors {
        val container = fill.compositeOver(backdrop, NuvioTokens.Opacity.disabled)
        val content = onFill.compositeOver(container, NuvioTokens.Opacity.disabled)
        return ResolvedButtonColors(container, content)
    }
}
