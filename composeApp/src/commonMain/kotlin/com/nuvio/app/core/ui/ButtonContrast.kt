package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** WCAG 2.x contrast floors used by the shared disabled controls. */
internal const val TEXT_CONTRAST_MIN = 4.5f
internal const val NON_TEXT_CONTRAST_MIN = 3f

/**
 * Share of [textSecondary] mixed over [borderDefault] for an off switch track.
 * 0.50 clears 3:1 against every theme card while the light thumb still clears 3:1 on the track.
 */
internal const val UNCHECKED_SWITCH_TRACK_BLEND = 0.50f

internal data class ResolvedButtonColors(
    val container: Color,
    val content: Color,
)

/** Opaque result of drawing [this] at [alpha] over an opaque [background]. */
internal fun Color.compositeOver(background: Color, alpha: Float): Color {
    val src = alpha.coerceIn(0f, 1f)
    val dst = 1f - src
    return Color(
        red = red * src + background.red * dst,
        green = green * src + background.green * dst,
        blue = blue * src + background.blue * dst,
        alpha = 1f,
    )
}

/** WCAG contrast ratio of two opaque colours. */
internal fun contrastRatio(foreground: Color, background: Color): Float {
    val lighter = max(foreground.luminance(), background.luminance())
    val darker = min(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Disabled filled button. The fill stays the accent blended at [NuvioTokens.Opacity.disabled]
 * over [backdrop], so it stays dimmer than the enabled control. The label keeps [onFill] when
 * that already clears 4.5:1; otherwise it uses white or near-black, whichever contrasts more.
 */
internal fun disabledFilledButtonColors(
    fill: Color,
    onFill: Color,
    backdrop: Color,
    fillAlpha: Float = NuvioTokens.Opacity.disabled,
): ResolvedButtonColors {
    val container = fill.copy(alpha = 1f).compositeOver(backdrop, fillAlpha)
    val preferred = onFill.copy(alpha = 1f)
    val content = if (contrastRatio(preferred, container) >= TEXT_CONTRAST_MIN) {
        preferred
    } else {
        higherContrast(Color.White, Color(0xFF111111), container)
    }
    return ResolvedButtonColors(container = container, content = content)
}

/**
 * Disabled low-emphasis button. The fill stays a faint tint. The label is the dimmest mix of
 * [content] over the fill that still clears 4.5:1, starting from the shared disabled opacity.
 */
internal fun disabledGhostButtonColors(
    fill: Color,
    content: Color,
    backdrop: Color,
    enabledFillAlpha: Float = fill.alpha,
    disabledFillScale: Float = NuvioTokens.Opacity.disabled,
): ResolvedButtonColors {
    val container = fill.copy(alpha = 1f).compositeOver(
        background = backdrop,
        alpha = (enabledFillAlpha * disabledFillScale).coerceIn(0f, 1f),
    )
    return ResolvedButtonColors(
        container = container,
        content = dimmestContentMeetingContrast(content.copy(alpha = 1f), container),
    )
}

internal fun uncheckedSwitchTrackColor(textSecondary: Color, borderDefault: Color): Color =
    textSecondary.compositeOver(borderDefault, UNCHECKED_SWITCH_TRACK_BLEND)

private fun higherContrast(first: Color, second: Color, background: Color): Color =
    if (contrastRatio(first, background) >= contrastRatio(second, background)) first else second

private fun dimmestContentMeetingContrast(content: Color, background: Color): Color {
    val floor = NuvioTokens.Opacity.disabled
    val atFloor = content.compositeOver(background, floor)
    if (contrastRatio(atFloor, background) >= TEXT_CONTRAST_MIN) return atFloor
    var low = floor
    var high = 1f
    var best = content.copy(alpha = 1f)
    repeat(16) {
        val mid = (low + high) / 2f
        val candidate = content.compositeOver(background, mid)
        if (contrastRatio(candidate, background) >= TEXT_CONTRAST_MIN) {
            best = candidate
            high = mid
        } else {
            low = mid
        }
    }
    return best
}
