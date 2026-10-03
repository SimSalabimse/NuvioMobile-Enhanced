package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import com.nuvio.app.isIos

/** Fully opaque fallback so labels stay readable when Reduce Transparency is on. */
internal val PlayerOpaqueScrim = Color(0xFF1C1C1E)

internal enum class PlayerChromeKind {
    FallbackFill,
    OpaqueScrim,
}

/**
 * iOS paints the call-site brush or color unless Reduce Transparency asks for a
 * solid scrim. The system material stays out of this choice: a clear effect
 * view would cover the fill.
 */
internal fun playerChromeKind(reduceTransparency: Boolean): PlayerChromeKind =
    if (reduceTransparency) PlayerChromeKind.OpaqueScrim else PlayerChromeKind.FallbackFill

/**
 * Dismiss veil behind a side panel. Android keeps the existing 34% slab.
 * iOS stays light enough that the picture shows through, and darkens when
 * Reduce Transparency is on.
 */
internal fun playerSidePanelScrimAlpha(ios: Boolean, reduceTransparency: Boolean): Float = when {
    !ios -> 0.34f
    reduceTransparency -> 0.55f
    else -> 0.16f
}

/** Menu body tint. Android cannot sample the player surface, so this is the frost. */
internal const val PlayerMenuFallbackAlpha = 0.42f

/** Tint over the iOS ultra-thin material when that material can see the picture. */
internal const val PlayerMenuMaterialTintAlpha = 0.28f

/** Veil around a centered card. The card body uses the menu frost. */
internal const val PlayerCenteredCardVeilAlpha = 0.28f

internal const val PlayerMenuRowAlpha = 0.08f
internal const val PlayerMenuHeaderPillAlpha = 0.12f
internal const val PlayerMenuSelectedAccentAlpha = 0.45f

internal val PlayerMenuRowFill = Color.White.copy(alpha = PlayerMenuRowAlpha)
internal val PlayerMenuHeaderPillFill = Color.White.copy(alpha = PlayerMenuHeaderPillAlpha)
internal val PlayerCenteredCardVeil = Color.Black.copy(alpha = PlayerCenteredCardVeilAlpha)

internal fun playerMenuBodyAlpha(reduceTransparency: Boolean, materialSamplesPicture: Boolean): Float = when {
    reduceTransparency -> 1f
    materialSamplesPicture -> PlayerMenuMaterialTintAlpha
    else -> PlayerMenuFallbackAlpha
}

/** iOS blur samples the picture only while its ancestors stay fully opaque. */
internal fun playerMaterialSamplesPicture(ios: Boolean, reduceTransparency: Boolean): Boolean =
    ios && !reduceTransparency

internal fun playerMenuUsesSystemMaterial(reduceTransparency: Boolean, materialSamplesPicture: Boolean): Boolean =
    playerChromeKind(reduceTransparency) != PlayerChromeKind.OpaqueScrim && materialSamplesPicture

/** An opaque accent selected row stays readable over the frost. */
internal fun playerMenuSelectedColor(accent: Color): Color =
    if (accent.alpha >= 0.999f) accent.copy(alpha = PlayerMenuSelectedAccentAlpha) else accent

@Composable
internal fun PlayerChromeBackdrop(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    fallbackColor: Color = Color.Transparent,
    fallbackBrush: Brush? = null,
) {
    val clipped = modifier.clip(shape)
    if (
        isIos &&
        playerChromeKind(playerReduceTransparencyEnabled()) == PlayerChromeKind.OpaqueScrim
    ) {
        Box(clipped.background(PlayerOpaqueScrim))
        return
    }
    if (fallbackBrush != null) {
        Box(clipped.background(fallbackBrush))
    } else {
        Box(clipped.background(fallbackColor))
    }
}

/**
 * Menu body. Android, and any platform that cannot sample the player, paints
 * [PlayerOpaqueScrim] at [PlayerMenuFallbackAlpha]. iOS stacks the ultra-thin
 * dark material under the 28% tint when that material can see the picture.
 * Reduce Transparency stays the opaque scrim, with no blur and no translucent tint.
 *
 * The caller keeps this composable's ancestors at full opacity. A fade on an
 * ancestor drops the iOS blur.
 */
@Composable
internal fun PlayerMenuBackdrop(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    val reduceTransparency = playerReduceTransparencyEnabled()
    val samplesPicture = playerMaterialSamplesPicture(isIos, reduceTransparency)
    val clipped = modifier.clip(shape)
    if (!playerMenuUsesSystemMaterial(reduceTransparency, samplesPicture)) {
        Box(
            clipped.background(
                PlayerOpaqueScrim.copy(alpha = playerMenuBodyAlpha(reduceTransparency, samplesPicture)),
            ),
        )
        return
    }
    Box(clipped) {
        IosSystemMaterial(Modifier.matchParentSize())
        Box(
            Modifier
                .matchParentSize()
                .background(PlayerOpaqueScrim.copy(alpha = PlayerMenuMaterialTintAlpha)),
        )
    }
}

/**
 * Ultra-thin dark material. A fade on an ancestor drops the effect, so menu
 * surfaces slide in and keep this view's ancestors fully opaque.
 */
@Composable
internal expect fun IosSystemMaterial(modifier: Modifier)

@Composable
internal expect fun playerReduceTransparencyEnabled(): Boolean

@Composable
internal expect fun playerReduceMotionEnabled(): Boolean
