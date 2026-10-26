package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.atLeastIosHitTarget
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.shimmer
import com.nuvio.app.features.streams.ProviderFilterRow
import com.nuvio.app.isIos
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.rememberRefreshRotation
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.collections_tab_all
import nuvio.composeapp.generated.resources.streams_refresh
import org.jetbrains.compose.resources.stringResource

internal val PlayerToolPanelPhoneWidth = 360.dp
internal val PlayerToolPanelWideWidth = 400.dp
internal val PlayerToolPanelWideShortestSide = 600.dp
internal val PlayerToolPanelCornerRadius = 24.dp
internal val PlayerToolPanelRowHeight = 52.dp
internal val PlayerToolPanelRowRadius = 12.dp
internal val PlayerToolPanelCloseSize = 44.dp
internal val PlayerToolPanelSwipeDismiss = 72.dp

/** Black slab under the pill fill so white text survives a bright frame. */
internal const val PlayerToolPanelBackingAlpha = 0.60f

/** Dim on the video beside an open tool. */
internal const val PlayerToolPanelScrimAlpha = 0.30f

/** Same iOS Reduce Transparency veil the older side panel used. */
internal const val PlayerToolPanelReduceTransparencyScrimAlpha = 0.55f

internal fun playerToolPanelIsWide(shortestSide: Dp): Boolean =
    shortestSide >= PlayerToolPanelWideShortestSide

/**
 * Width of the tool panel inside [containerWidth], never crossing [endInset].
 * Phones use 360; iPad and other wide windows use 400.
 */
internal fun playerToolPanelWidth(containerWidth: Dp, endInset: Dp, wide: Boolean): Dp {
    val target = if (wide) PlayerToolPanelWideWidth else PlayerToolPanelPhoneWidth
    val available = (containerWidth - endInset).coerceAtLeast(0.dp)
    return minOf(target, available)
}

internal fun playerToolPanelScrimAlpha(ios: Boolean, reduceTransparency: Boolean): Float = when {
    ios && reduceTransparency -> PlayerToolPanelReduceTransparencyScrimAlpha
    else -> PlayerToolPanelScrimAlpha
}

internal enum class PlayerToolBackAction {
    Ignore,
    Back,
    Dismiss,
}

/** System back steps into the panel when it has a sub-page, and does nothing while a send is locked. */
internal fun playerToolPanelBackAction(dismissLocked: Boolean, hasBack: Boolean): PlayerToolBackAction = when {
    dismissLocked -> PlayerToolBackAction.Ignore
    hasBack -> PlayerToolBackAction.Back
    else -> PlayerToolBackAction.Dismiss
}

/**
 * One right-hand panel for every player tool. The glass is the same flat fill and
 * edge the pills use, over a 60% black backing. Reduce Transparency stays opaque.
 */
@Composable
internal fun PlayerToolPanel(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    dismissLocked: Boolean = false,
    footer: @Composable (ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduceMotion = playerReduceMotionEnabled()
    val reduceTransparency = playerReduceTransparencyEnabled()
    val opaque = isIos && playerChromeKind(reduceTransparency) == PlayerChromeKind.OpaqueScrim
    val scrimAlpha = playerToolPanelScrimAlpha(ios = isIos, reduceTransparency = reduceTransparency)
    val fadeEnter = if (reduceMotion) EnterTransition.None else fadeIn(tween(200))
    val fadeExit = if (reduceMotion) ExitTransition.None else fadeOut(tween(160))
    val slideEnter = if (reduceMotion) EnterTransition.None else slideInHorizontally(tween(250)) { it }
    val slideExit = if (reduceMotion) ExitTransition.None else slideOutHorizontally(tween(200)) { it }
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    var dragOutward by remember { mutableFloatStateOf(0f) }
    val outwardSign = if (layoutDirection == LayoutDirection.Ltr) 1f else -1f

    LaunchedEffect(visible) {
        if (!visible) dragOutward = 0f
    }

    PlatformBackHandler(enabled = visible, onBack = {
        when (playerToolPanelBackAction(dismissLocked = dismissLocked, hasBack = onBack != null)) {
            PlayerToolBackAction.Ignore -> Unit
            PlayerToolBackAction.Back -> onBack?.invoke()
            PlayerToolBackAction.Dismiss -> onDismiss()
        }
    })

    AnimatedVisibility(
        visible = visible,
        enter = fadeEnter,
        exit = fadeExit,
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val endInset = playerToolPanelEndInset()
            val panelWidth = playerToolPanelWidth(
                containerWidth = maxWidth,
                endInset = endInset,
                wide = playerToolPanelIsWide(minOf(maxWidth, maxHeight)),
            )
            val shape = playerToolPanelShape(layoutDirection)
            val scrimInteraction = remember { MutableInteractionSource() }
            val dismissThreshold = with(density) { PlayerToolPanelSwipeDismiss.toPx() }
            val panelWidthPx = with(density) { panelWidth.toPx() }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = endInset),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.Black.copy(alpha = scrimAlpha))
                        .clickable(
                            interactionSource = scrimInteraction,
                            indication = null,
                            enabled = !dismissLocked,
                            onClick = onDismiss,
                        ),
                )
                AnimatedVisibility(
                    visible = visible,
                    enter = slideEnter,
                    exit = slideExit,
                ) {
                    Box(
                        modifier = Modifier
                            .width(panelWidth)
                            .fillMaxHeight()
                            .graphicsLayer { translationX = dragOutward * outwardSign }
                            .pointerInput(dismissLocked, dismissThreshold, outwardSign, panelWidthPx) {
                                if (dismissLocked) return@pointerInput
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, amount ->
                                        change.consume()
                                        dragOutward = (dragOutward + amount * outwardSign)
                                            .coerceIn(0f, panelWidthPx)
                                    },
                                    onDragEnd = {
                                        if (dragOutward >= dismissThreshold) onDismiss()
                                        dragOutward = 0f
                                    },
                                    onDragCancel = { dragOutward = 0f },
                                )
                            }
                            .clip(shape),
                    ) {
                        PlayerToolPanelFill(opaque = opaque, shape = shape)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(playerToolPanelVerticalInsets())
                                .imePadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            PlayerToolPanelHeader(
                                title = title,
                                subtitle = subtitle,
                                onBack = onBack,
                                onClose = onDismiss,
                                closeEnabled = playerToolPanelBackAction(
                                    dismissLocked = dismissLocked,
                                    hasBack = false,
                                ) == PlayerToolBackAction.Dismiss,
                                actions = headerActions,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                content = content,
                            )
                            footer?.invoke(this)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun playerToolPanelAndroidSafeInsets(): WindowInsets =
    WindowInsets.displayCutout.union(WindowInsets.navigationBars)

@Composable
private fun playerToolPanelEndInset(): Dp {
    val layoutDirection = LocalLayoutDirection.current
    val insets = if (isIos) WindowInsets.safeContent else playerToolPanelAndroidSafeInsets()
    return insets.only(WindowInsetsSides.End).asPaddingValues().calculateEndPadding(layoutDirection)
}

@Composable
private fun playerToolPanelVerticalInsets(): WindowInsets {
    return if (isIos) {
        WindowInsets.safeContent.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
    } else {
        playerToolPanelAndroidSafeInsets().only(WindowInsetsSides.Top + WindowInsetsSides.Bottom)
    }
}

private fun playerToolPanelShape(layoutDirection: LayoutDirection): RoundedCornerShape {
    return if (layoutDirection == LayoutDirection.Ltr) {
        RoundedCornerShape(
            topStart = PlayerToolPanelCornerRadius,
            bottomStart = PlayerToolPanelCornerRadius,
        )
    } else {
        RoundedCornerShape(
            topEnd = PlayerToolPanelCornerRadius,
            bottomEnd = PlayerToolPanelCornerRadius,
        )
    }
}

@Composable
private fun BoxScope.PlayerToolPanelFill(opaque: Boolean, shape: Shape) {
    if (opaque) {
        Box(Modifier.matchParentSize().background(PlayerOpaqueScrim))
        return
    }
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = PlayerToolPanelBackingAlpha)))
    Box(Modifier.matchParentSize().background(PlayerGlassFallback))
    Box(Modifier.matchParentSize().border(0.75.dp, PlayerGlassRim, shape))
}

@Composable
private fun PlayerToolPanelHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    closeEnabled: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) {
                PlayerToolIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                    onClick = onBack,
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
            PlayerToolIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.action_close),
                onClick = onClose,
                enabled = closeEnabled,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayerToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(PlayerToolPanelCloseSize)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(PlayerGlassFallback)
            .border(0.75.dp, PlayerGlassRim, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun PlayerToolSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(top = 14.dp, bottom = 4.dp),
        color = MaterialTheme.nuvio.colors.textMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun PlayerToolRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: String? = null,
    supportingColor: Color? = null,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val primary = if (selected) tokens.colors.onAccent else Color.White
    val secondary = supportingColor ?: if (selected) {
        tokens.colors.onAccent.copy(alpha = 0.72f)
    } else {
        Color.White.copy(alpha = 0.72f)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PlayerToolPanelRowHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(PlayerToolPanelRowRadius))
            .background(if (selected) tokens.colors.accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = primary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    text = supporting,
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                color = secondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun PlayerSidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val backgroundInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }
    val reduceMotion = playerReduceMotionEnabled()
    val scrimAlpha = playerSidePanelScrimAlpha(
        ios = isIos,
        reduceTransparency = playerReduceTransparencyEnabled(),
    )
    val fadeEnter = if (reduceMotion) EnterTransition.None else fadeIn(tween(200))
    val fadeExit = if (reduceMotion) ExitTransition.None else fadeOut(tween(160))
    val slideEnter = if (reduceMotion) EnterTransition.None else slideInHorizontally(tween(250)) { it }
    val slideExit = if (reduceMotion) ExitTransition.None else slideOutHorizontally(tween(200)) { it }

    PlatformBackHandler(enabled = visible, onBack = onDismiss)

    AnimatedVisibility(
        visible = visible,
        enter = fadeEnter,
        exit = fadeExit,
        modifier = modifier,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = backgroundInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            val resolvedWidth = minOf(maxWidth, width)
            val shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

            AnimatedVisibility(
                visible = visible,
                enter = slideEnter,
                exit = slideExit,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Box(
                    modifier = Modifier
                        .width(resolvedWidth)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable(
                            interactionSource = panelInteraction,
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    PlayerChromeBackdrop(
                        modifier = Modifier.matchParentSize(),
                        shape = shape,
                        fallbackColor = tokens.colors.surfaceElevated,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                if (isIos) {
                                    WindowInsets.safeContent.only(
                                        WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End,
                                    )
                                } else {
                                    WindowInsets.navigationBars.only(WindowInsetsSides.End)
                                },
                            ),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlayerPanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            color = tokens.colors.textPrimary,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = actions,
        )
    }
}

@Composable
internal fun PlayerDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else tokens.opacity.disabled)
            .heightIn(min = 0.dp.atLeastIosHitTarget())
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tokens.colors.textSecondary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PlayerModalLoading(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        NuvioLoadingIndicator(
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
internal fun PlayerProviderFilterRow(
    streamsUiState: StreamsUiState,
    onFilterSelected: (String?) -> Unit,
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ProviderFilterRow(
        groups = streamsUiState.groups,
        selectedFilter = streamsUiState.selectedFilter,
        onFilterSelected = onFilterSelected,
        onRefresh = onRefresh,
        isRefreshing = streamsUiState.isAnyLoading || streamsUiState.groups.any { it.isLoading },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        spacing = 16.dp,
        refreshChip = { isLoading, onClick ->
            val rotation = rememberRefreshRotation(isLoading)
            AddonFilterChip(
                label = stringResource(Res.string.streams_refresh),
                isSelected = false,
                onClick = onClick,
                icon = Icons.Rounded.Refresh,
                iconModifier = Modifier.graphicsLayer { rotationZ = rotation.value },
            )
        },
    ) { group, isSelected, onClick ->
        AddonFilterChip(
            label = group?.addonName ?: stringResource(Res.string.collections_tab_all),
            isSelected = isSelected,
            isLoading = group?.isLoading == true,
            hasError = group?.error != null,
            onClick = onClick,
        )
    }
}

@Composable
private fun AddonFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    icon: ImageVector? = null,
    iconModifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val containerColor = when {
        hasError -> tokens.colors.danger.copy(alpha = 0.06f)
        isSelected -> tokens.colors.accent
        else -> tokens.colors.surfaceCard
    }
    val contentColor = when {
        hasError -> tokens.colors.danger
        isSelected -> tokens.colors.onAccent
        else -> tokens.colors.textSecondary
    }

    Box(
        modifier = modifier
            .heightIn(min = 0.dp.atLeastIosHitTarget())
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .border(
                1.dp,
                if (hasError) tokens.colors.danger.copy(alpha = 0.7f) else tokens.colors.borderDefault,
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp).then(iconModifier),
            )
        } else {
            Text(
                text = label,
                modifier = Modifier.shimmer(isLoading),
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
