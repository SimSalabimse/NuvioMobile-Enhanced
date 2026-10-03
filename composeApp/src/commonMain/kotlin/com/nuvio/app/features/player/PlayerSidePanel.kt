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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.shimmer
import com.nuvio.app.features.streams.ProviderFilterRow
import com.nuvio.app.isIos
import com.nuvio.app.features.streams.StreamsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_tab_all
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerSidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val experimentalOverlay = LocalExperimentalPlayerOverlay.current
        val sizeClass = playerSizeClassForOverlay(maxWidth, maxHeight, experimentalOverlay)
        val leadingInset = WindowInsets.safeContent
            .asPaddingValues()
            .calculateStartPadding(LocalLayoutDirection.current)
        val resolvedWidth = if (sizeClass == PlayerSizeClass.T) {
            minOf(maxWidth, width)
        } else {
            playerTrailingPanelWidth(maxWidth, leadingInset).coerceAtMost(maxWidth)
        }
        val shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)

        AnimatedVisibility(
            visible = visible,
            enter = fadeEnter,
            exit = fadeExit,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = backgroundInteraction,
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

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
                PlayerMenuBackdrop(
                    modifier = Modifier.matchParentSize(),
                    shape = shape,
                )
                CompositionLocalProvider(LocalPlayerSizeClass provides sizeClass) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (!experimentalOverlay) {
                                    if (isIos) {
                                        Modifier.windowInsetsPadding(
                                            WindowInsets.safeContent.only(
                                                WindowInsetsSides.Top +
                                                    WindowInsetsSides.Bottom +
                                                    WindowInsetsSides.End,
                                            ),
                                        )
                                    } else {
                                        Modifier
                                    }
                                } else if (sizeClass == PlayerSizeClass.T) {
                                    Modifier.windowInsetsPadding(playerPanelSafeInsets())
                                } else {
                                    Modifier
                                        .windowInsetsPadding(playerPanelSafeInsets())
                                        .padding(16.dp)
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
    val phone = LocalPlayerSizeClass.current != PlayerSizeClass.T

    val titleColor = tokens.colors.textPrimary
    val titleStyle = if (phone) {
        MaterialTheme.typography.titleMedium.copy(
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
    } else {
        MaterialTheme.typography.headlineSmall
    }

    if (!phone) {
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
                color = titleColor,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = actions,
            )
        }
        return
    }

    // Measure the title at its full one-line width. Move the pills down when
    // they and a 12dp gap would not leave that width. Do not ellipsize the
    // title to keep the pills beside it.
    SubcomposeLayout(modifier = modifier.fillMaxWidth()) { constraints ->
        val gap = PlayerPanelHeaderGapDp.dp.roundToPx()
        val lineGap = 8.dp.roundToPx()
        val actionPlaceables = subcompose("actions") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        }
        val actionsWidth = actionPlaceables.maxOfOrNull { it.width } ?: 0
        val actionsHeight = actionPlaceables.maxOfOrNull { it.height } ?: 0
        val titleMeasurables = subcompose("title") {
            Text(
                text = title,
                color = titleColor,
                style = titleStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val titleWidth = titleMeasurables.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0
        val stacksActions = phonePanelHeaderStacksActions(
            titleWidthPx = titleWidth,
            actionsWidthPx = actionsWidth,
            maxWidthPx = constraints.maxWidth,
            gapPx = gap,
        )
        val fitsBesideTitle = !stacksActions
        val titleMaxWidth = if (fitsBesideTitle) {
            titleWidth.coerceAtMost(constraints.maxWidth)
        } else {
            constraints.maxWidth
        }
        val titlePlaceables = titleMeasurables.map { measurable ->
            measurable.measure(
                constraints.copy(minWidth = 0, minHeight = 0, maxWidth = titleMaxWidth.coerceAtLeast(0)),
            )
        }
        val titleHeight = titlePlaceables.maxOfOrNull { it.height } ?: 0
        val height = if (fitsBesideTitle) {
            maxOf(titleHeight, actionsHeight)
        } else {
            titleHeight + if (actionsHeight > 0) lineGap + actionsHeight else 0
        }
        layout(constraints.maxWidth, height) {
            if (fitsBesideTitle) {
                titlePlaceables.forEach { placeable ->
                    placeable.placeRelative(0, (height - placeable.height) / 2)
                }
                actionPlaceables.forEach { placeable ->
                    placeable.placeRelative(
                        constraints.maxWidth - placeable.width,
                        (height - placeable.height) / 2,
                    )
                }
            } else {
                titlePlaceables.forEach { placeable -> placeable.placeRelative(0, 0) }
                val actionsTop = titleHeight + lineGap
                actionPlaceables.forEach { placeable ->
                    placeable.placeRelative(constraints.maxWidth - placeable.width, actionsTop)
                }
            }
        }
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
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(playerMenuHeaderPillFill())
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
    modifier: Modifier = Modifier,
) {
    ProviderFilterRow(
        groups = streamsUiState.groups,
        selectedFilter = streamsUiState.selectedFilter,
        onFilterSelected = onFilterSelected,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        spacing = 16.dp,
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
) {
    val tokens = MaterialTheme.nuvio
    val containerColor = when {
        hasError -> tokens.colors.danger.copy(alpha = 0.06f)
        isSelected -> resolvedPlayerMenuSelectedColor(tokens.colors.accent)
        else -> playerMenuRowFill()
    }
    val contentColor = when {
        hasError -> tokens.colors.danger
        isSelected -> tokens.colors.onAccent
        else -> tokens.colors.textSecondary
    }

    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .border(
                1.dp,
                if (hasError) tokens.colors.danger.copy(alpha = 0.7f) else tokens.colors.borderDefault,
                RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
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
