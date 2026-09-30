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
    SystemMaterial,
    OpaqueScrim,
}

/** iOS uses a system material unless Reduce Transparency asks for a solid scrim. */
internal fun playerChromeKind(reduceTransparency: Boolean): PlayerChromeKind =
    if (reduceTransparency) PlayerChromeKind.OpaqueScrim else PlayerChromeKind.SystemMaterial

/**
 * Dismiss veil behind a side panel. Android keeps the existing 34% slab.
 * iOS stays light enough that the picture shows through, and darkens when
 * Reduce Transparency removes the panel material.
 */
internal fun playerSidePanelScrimAlpha(ios: Boolean, reduceTransparency: Boolean): Float = when {
    !ios -> 0.34f
    reduceTransparency -> 0.55f
    else -> 0.16f
}

@Composable
internal fun PlayerChromeBackdrop(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    fallbackColor: Color = Color.Transparent,
    fallbackBrush: Brush? = null,
) {
    val clipped = modifier.clip(shape)
    if (!isIos) {
        if (fallbackBrush != null) {
            Box(clipped.background(fallbackBrush))
        } else {
            Box(clipped.background(fallbackColor))
        }
        return
    }
    if (playerChromeKind(playerReduceTransparencyEnabled()) == PlayerChromeKind.OpaqueScrim) {
        Box(clipped.background(PlayerOpaqueScrim))
    } else {
        IosSystemMaterial(clipped)
    }
}

@Composable
internal expect fun IosSystemMaterial(modifier: Modifier)

@Composable
internal expect fun playerReduceTransparencyEnabled(): Boolean

@Composable
internal expect fun playerReduceMotionEnabled(): Boolean
