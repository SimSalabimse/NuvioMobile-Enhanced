package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.glass.NuvioBackdrop
import com.nuvio.app.core.ui.glass.isLiquidGlassSupported
import com.nuvio.app.core.ui.glass.liquidGlassSurface
import com.nuvio.app.core.ui.glass.nuvioBackdropSource

private val SheetShape = RoundedCornerShape(NuvioTokens.Space.s28)

/** Top corners only, so an edge-to-edge sheet meets the bottom of the screen in a straight line. */
private val SheetTopShape = RoundedCornerShape(
    topStart = NuvioTokens.Space.s28,
    topEnd = NuvioTokens.Space.s28,
)

internal const val FLAG_SHEET_DETENT_ID = "nuvio.flag"

/** Widest flag card. Wider windows keep the player visible beside it. */
internal const val FLAG_SHEET_CARD_WIDTH = 560.0

/**
 * Height of the flag card inside the sheet's maximum detent.
 * Short heights (landscape phones) stay at most 72% and leave 72pt so the card cannot cover
 * the player. Tall screens cap at 340pt, enough for the compact form. A very short height
 * can still fill so the form fits.
 */
internal fun flagSheetDetentHeight(maximumPoints: Double): Double {
    if (maximumPoints <= 0.0) return 0.0
    if (maximumPoints <= 280.0) return maximumPoints
    if (maximumPoints <= 500.0) {
        return minOf(maximumPoints - 72.0, maximumPoints * 0.72)
    }
    return minOf(maximumPoints * 0.5, 340.0)
}

internal fun flagSheetCardWidth(containerWidth: Double): Double {
    if (containerWidth <= 0.0) return FLAG_SHEET_CARD_WIDTH
    return minOf(FLAG_SHEET_CARD_WIDTH, containerWidth)
}

// Keep the sheet background treatment consistent between the sheet and pinned content inside it.
internal fun Modifier.nuvioSheetSurface(
    backdrop: NuvioBackdrop?,
    shape: Shape,
    containerColor: Color,
    withHighlight: Boolean = true,
): Modifier {
    val readyBackdrop = backdrop?.takeIf { it.isReady && isLiquidGlassSupported() }
    return liquidGlassSurface(
        backdrop = readyBackdrop,
        shape = shape,
        tint = containerColor.copy(alpha = containerColor.alpha * 0.65f),
        fallback = containerColor,
        blurRadius = 24.dp,
        refractionHeight = 16.dp,
        refractionAmount = 32.dp,
        withHighlight = withHighlight,
    )
}

@Stable
class NuvioSheetBackdrop(val backdrop: NuvioBackdrop) {
    var activeSheets by mutableIntStateOf(0)
}

val LocalNuvioSheetBackdrop = staticCompositionLocalOf<NuvioSheetBackdrop?> { null }

@Composable
fun NuvioSheetBackdropHost(
    sheetBackdrop: NuvioSheetBackdrop,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalNuvioSheetBackdrop provides sheetBackdrop) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (sheetBackdrop.activeSheets > 0 && isLiquidGlassSupported()) Modifier.nuvioBackdropSource(sheetBackdrop.backdrop) else Modifier),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuvioModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.nuvio.colors.surfaceSheet,
    contentColor: Color = MaterialTheme.nuvio.colors.textPrimary,
    showDragHandle: Boolean = true,
    fullHeight: Boolean = false,
    liquidGlass: Boolean = false,
    /**
     * Draws the sheet colour to the physical bottom of the screen, behind the home indicator
     * or navigation bar, and pads the content by that inset.
     */
    edgeToEdgeBottom: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (usesNativeNuvioBottomSheet) {
        NuvioNativeModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            containerColor = containerColor,
            contentColor = contentColor,
            showDragHandle = showDragHandle,
            fullHeight = fullHeight,
            liquidGlass = liquidGlass,
            edgeToEdgeBottom = edgeToEdgeBottom,
            content = content,
        )
    } else {
        val tokens = MaterialTheme.nuvio
        val sheetBackdrop = LocalNuvioSheetBackdrop.current
        if (sheetBackdrop != null) {
            DisposableEffect(sheetBackdrop) {
                sheetBackdrop.activeSheets += 1
                onDispose { sheetBackdrop.activeSheets -= 1 }
            }
        }
        val sheetGlassReady = sheetBackdrop != null && sheetBackdrop.backdrop.isReady && isLiquidGlassSupported()
        val sheetShape = if (edgeToEdgeBottom) SheetTopShape else SheetShape
        // Zero insets let the coloured column reach the screen edge. The navigation-bar padding
        // sits inside that colour, so the home indicator / gesture bar is covered and the buttons are not.
        val bottomInset = if (edgeToEdgeBottom) {
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        } else {
            0.dp
        }
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            shape = RectangleShape,
            containerColor = Color.Transparent,
            contentColor = contentColor,
            scrimColor = tokens.colors.overlayScrim,
            dragHandle = null,
            contentWindowInsets = {
                if (edgeToEdgeBottom) WindowInsets(0, 0, 0, 0) else BottomSheetDefaults.windowInsets
            },
        ) {
            CompositionLocalProvider(LocalBottomInsetsConsumed provides true) {
                Column(
                    modifier = modifier
                        .padding(
                            start = NuvioTokens.Space.s10,
                            top = NuvioTokens.Space.s10,
                            end = NuvioTokens.Space.s10,
                            bottom = if (edgeToEdgeBottom) 0.dp else NuvioTokens.Space.s10,
                        )
                        .clip(sheetShape)
                        .nuvioSheetSurface(
                            backdrop = sheetBackdrop?.backdrop,
                            shape = sheetShape,
                            containerColor = containerColor,
                        )
                        .then(if (sheetGlassReady) Modifier else Modifier.border(tokens.borders.thin, SurfaceEdge, sheetShape))
                        .padding(bottom = bottomInset),
                ) {
                    if (showDragHandle) {
                        NuvioBottomSheetDragHandle(Modifier.align(Alignment.CenterHorizontally))
                    }
                    content()
                }
            }
        }
    }
}

@Composable
fun NuvioBottomSheetDivider(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    HorizontalDivider(
        modifier = modifier.padding(horizontal = tokens.spacing.screenHorizontal),
        thickness = tokens.borders.hairline,
        color = tokens.colors.borderDefault,
    )
}

@Composable
fun NuvioBottomSheetActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s8)
            .heightIn(min = NuvioTokens.Space.s56)
            .clip(tokens.shapes.compactCard)
            .background(if (selected) tokens.colors.accent.copy(alpha = tokens.opacity.hover) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(NuvioTokens.Space.s8),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(NuvioTokens.Space.s36)
                    .clip(tokens.shapes.compactCard)
                    .background(tokens.colors.accent.copy(alpha = tokens.opacity.selected)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(NuvioTokens.Icon.md),
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailingContent?.invoke(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
suspend fun dismissNuvioBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    if (usesNativeNuvioBottomSheet) {
        dismissNativeNuvioBottomSheet()
    } else if (sheetState.isVisible) {
        sheetState.hide()
    }
    onDismiss()
}

@Composable
private fun NuvioBottomSheetDragHandle(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .padding(top = NuvioTokens.Space.s10, bottom = NuvioTokens.Space.s4)
            .size(width = NuvioTokens.Space.s36, height = NuvioTokens.Space.s4)
            .clip(tokens.shapes.chip)
            .background(tokens.colors.borderStrong),
    )
}
