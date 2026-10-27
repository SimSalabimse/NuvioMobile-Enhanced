package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun playerOrientationToggleIcon(isPortraitPlayback: Boolean): ImageVector =
    if (isPortraitPlayback) Icons.Rounded.StayCurrentLandscape else Icons.Rounded.StayCurrentPortrait

internal fun playerOrientationToggleLabel(isPortraitPlayback: Boolean): StringResource =
    if (isPortraitPlayback) Res.string.player_action_rotate_landscape else Res.string.player_action_rotate_portrait

internal enum class PlayerFlagHome {
    TopPill,
    LegacyHeader,
    Hidden,
}

/** One Flag per layout: the legacy header, or the new top pill. */
internal fun playerFlagHome(useLegacyLayout: Boolean, submitAvailable: Boolean): PlayerFlagHome = when {
    !submitAvailable -> PlayerFlagHome.Hidden
    useLegacyLayout -> PlayerFlagHome.LegacyHeader
    else -> PlayerFlagHome.TopPill
}

internal enum class PlayerToolbarSlot {
    Download,
    PictureInPicture,
    Rotate,
    Party,
    Cast,
    Flag,
    Lock,
}

/**
 * Fixed spots, left to right. A missing action drops out, so Flag stays
 * immediately before Lock on the trailing edge.
 */
internal fun playerToolbarSlots(
    download: Boolean = false,
    pictureInPicture: Boolean = false,
    rotate: Boolean = false,
    party: Boolean = false,
    cast: Boolean = false,
    flag: Boolean = false,
): List<PlayerToolbarSlot> = buildList {
    if (download) add(PlayerToolbarSlot.Download)
    if (pictureInPicture) add(PlayerToolbarSlot.PictureInPicture)
    if (rotate) add(PlayerToolbarSlot.Rotate)
    if (party) add(PlayerToolbarSlot.Party)
    if (cast) add(PlayerToolbarSlot.Cast)
    if (flag) add(PlayerToolbarSlot.Flag)
    add(PlayerToolbarSlot.Lock)
}

@Composable
internal fun PlayerToolbar(
    isLocked: Boolean,
    onLockToggle: () -> Unit,
    onBack: () -> Unit,
    onSubmitIntroClick: (() -> Unit)? = null,
    onCastClick: (() -> Unit)? = null,
    isCasting: Boolean = false,
    downloadAction: (@Composable (buttonSize: Dp, iconSize: Dp) -> Unit)? = null,
    modifier: Modifier = Modifier,
    onPartyClick: (() -> Unit)? = null,
    partyActive: Boolean = false,
    onOrientationToggleClick: (() -> Unit)? = null,
    isPortraitPlayback: Boolean = false,
    onPictureInPictureClick: (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        // Toolbar actions share one liquid-glass capsule; close sits in its own glass circle.
        Row(
            modifier = Modifier.playerLiquidGlass(
                shape = PlayerGlassCapsule,
                refractionHeight = 8.dp,
                refractionAmount = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            playerToolbarSlots(
                download = downloadAction != null,
                pictureInPicture = onPictureInPictureClick != null,
                rotate = onOrientationToggleClick != null,
                party = onPartyClick != null,
                cast = onCastClick != null,
                flag = onSubmitIntroClick != null,
            ).forEach { slot ->
                when (slot) {
                    PlayerToolbarSlot.Download -> downloadAction?.invoke(48.dp, 24.dp)
                    PlayerToolbarSlot.PictureInPicture -> onPictureInPictureClick?.let {
                        PlayerAction(
                            description = stringResource(Res.string.compose_player_picture_in_picture),
                            icon = Icons.Rounded.PictureInPictureAlt,
                            onClick = it,
                        )
                    }
                    PlayerToolbarSlot.Rotate -> onOrientationToggleClick?.let {
                        PlayerAction(
                            description = stringResource(playerOrientationToggleLabel(isPortraitPlayback)),
                            icon = playerOrientationToggleIcon(isPortraitPlayback),
                            onClick = it,
                        )
                    }
                    PlayerToolbarSlot.Party -> if (onPartyClick != null) {
                        PlayerAction(
                            description = stringResource(Res.string.party_title),
                            icon = Icons.Rounded.Groups,
                            onClick = onPartyClick,
                            tint = if (partyActive) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    PlayerToolbarSlot.Cast -> onCastClick?.let {
                        PlayerAction(
                            description = stringResource(Res.string.player_cast),
                            icon = if (isCasting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                            onClick = it,
                        )
                    }
                    PlayerToolbarSlot.Flag -> if (onSubmitIntroClick != null) {
                        PlayerAction(
                            description = stringResource(Res.string.submit_intro_action),
                            icon = Icons.Rounded.Flag,
                            onClick = onSubmitIntroClick,
                        )
                    }
                    PlayerToolbarSlot.Lock -> PlayerAction(
                        description = stringResource(
                            if (isLocked) Res.string.compose_player_unlock_controls else Res.string.compose_player_lock_controls,
                        ),
                        icon = if (isLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        onClick = onLockToggle,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier.playerLiquidGlass(CircleShape, refractionHeight = 8.dp, refractionAmount = 12.dp),
            shape = CircleShape,
            containerColor = Color.Transparent,
            contentColor = Color.White,
            buttonSize = 48.dp,
            iconSize = 24.dp,
            contentDescription = stringResource(Res.string.compose_player_close),
        )
    }
}

private const val ShowUpstreamStreamInfoAction = false

@Composable
internal fun PlayerControlActions(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long = 0L,
    playbackClock: PlayerPlaybackClock? = null,
    scrubbingPositionMs: Long? = null,
    showRemainingTime: Boolean,
    onRuntimeClick: () -> Unit,
    metrics: PlayerLayoutMetrics,
    resizeMode: PlayerResizeMode,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSourcesClick: (() -> Unit)?,
    onEpisodesClick: (() -> Unit)?,
    onChaptersClick: (() -> Unit)? = null,
    onNextEpisodeClick: (() -> Unit)?,
    onSpeedClick: () -> Unit,
    speedPickerVisible: Boolean = false,
    onSpeedSelected: (Float) -> Unit = {},
    onSpeedPickerDismiss: () -> Unit = {},
    onResizeModeClick: () -> Unit,
    onVideoSettingsClick: (() -> Unit)?,
    onOpenInExternalPlayer: (() -> Unit)?,
    onStreamInfoClick: () -> Unit,
    qualityLabel: String? = null,
    onQualityClick: (() -> Unit)? = null,
    onSwitchEngineClick: (() -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null,
    onRateClick: (() -> Unit)? = null,
    userRating: Int? = null,
    onInteraction: () -> Unit,
    onMarkSegmentClick: (() -> Unit)? = null,
    onLiveChannelsClick: (() -> Unit)? = null,
    onToolsMenuOpenChange: (Boolean) -> Unit = {},
) {
    val primaryActions = listOfNotNull(
        onNextEpisodeClick?.let {
            PlayerControlAction(
                stringResource(Res.string.player_next_episode), it,
                icon = Icons.Rounded.SkipNext, iconSize = 40.dp,
            )
        },
        PlayerControlAction(
            "${stringResource(Res.string.compose_player_speed)} ${formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed)}",
            onSpeedClick, icon = Icons.Rounded.Speed,
            popup = {
                PlayerSpeedPopup(
                    visible = speedPickerVisible,
                    currentSpeed = playbackSnapshot.playbackSpeed,
                    onSpeedSelected = onSpeedSelected,
                    onDismiss = onSpeedPickerDismiss,
                )
            },
        ),
        PlayerControlAction(
            stringResource(resizeMode.labelRes), onResizeModeClick,
            painter = appIconPainter(AppIconResource.PlayerAspectRatio),
        ),
        PlayerControlAction(
            stringResource(Res.string.compose_player_subtitles), onSubtitleClick,
            painter = appIconPainter(AppIconResource.PlayerSubtitles),
        ),
        onChaptersClick?.let {
            PlayerControlAction(
                stringResource(Res.string.player_chapters), it,
                icon = Icons.AutoMirrored.Rounded.ListAlt,
            )
        },
    )
    val secondaryActions = listOfNotNull(
        onRateClick?.let {
            PlayerControlAction(
                userRating?.let { rating -> stringResource(Res.string.user_rating_action_rated, rating) }
                    ?: stringResource(Res.string.user_rating_action_rate),
                it,
                icon = if (userRating != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            )
        },
        onOpenInExternalPlayer?.let {
            PlayerControlAction(
                stringResource(Res.string.streams_open_external_player), it,
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
            )
        },
        onInfoClick?.let {
            PlayerControlAction(
                stringResource(Res.string.compose_player_playback_info), it,
                icon = Icons.Rounded.Info,
            )
        },
        onStreamInfoClick.takeIf { ShowUpstreamStreamInfoAction }?.let {
            PlayerControlAction(
                stringResource(Res.string.cd_stream_info), onStreamInfoClick,
                icon = Icons.Rounded.Info,
            )
        },
        onSwitchEngineClick?.let {
            PlayerControlAction(
                stringResource(Res.string.cd_switch_player_engine), it,
                icon = Icons.Rounded.SwapHoriz,
            )
        },
    )
    val menuActions = listOfNotNull(
        onMarkSegmentClick?.let {
            PlayerControlAction(stringResource(Res.string.player_tools_mark_segment), it, icon = Icons.Rounded.Flag)
        },
        PlayerControlAction(
            stringResource(Res.string.compose_player_subtitles), onSubtitleClick,
            painter = appIconPainter(AppIconResource.PlayerSubtitles),
        ),
        PlayerControlAction(
            stringResource(Res.string.compose_player_audio), onAudioClick,
            painter = appIconPainter(AppIconResource.PlayerAudioFilled),
        ),
        onSourcesClick?.let {
            PlayerControlAction(
                stringResource(Res.string.compose_player_sources), it,
                painter = appIconPainter(AppIconResource.PlayerSource),
            )
        },
        onEpisodesClick?.let {
            PlayerControlAction(
                stringResource(Res.string.compose_player_episodes), it,
                painter = appIconPainter(AppIconResource.PlayerEpisodes),
            )
        },
        onVideoSettingsClick?.let {
            PlayerControlAction(
                stringResource(Res.string.player_action_video_settings), it,
                icon = Icons.Rounded.Build,
            )
        },
        onQualityClick?.let {
            PlayerControlAction(
                qualityLabel?.takeIf { label -> label.isNotBlank() } ?: "Quality", it,
                icon = Icons.Rounded.HighQuality,
            )
        },
        onLiveChannelsClick?.let {
            PlayerControlAction(stringResource(Res.string.compose_player_channels), it, icon = Icons.Rounded.Tv)
        },
    ) + secondaryActions
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchorTopPx by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()
    LaunchedEffect(menuOpen) { onToolsMenuOpenChange(menuOpen) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PlayerTimelineContentInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .playerLiquidGlass(
                            shape = PlayerGlassCapsule,
                            refractionHeight = 8.dp,
                            refractionAmount = 12.dp,
                        )
                        .horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    primaryActions.forEach { action ->
                        Box {
                            PlayerAction(
                                description = action.description,
                                onClick = {
                                    onInteraction()
                                    action.onClick()
                                },
                                icon = action.icon,
                                painter = action.painter,
                                iconSize = action.iconSize,
                            )
                            action.popup?.invoke()
                        }
                    }
                    Box(
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            menuAnchorTopPx = coordinates.positionInWindow().y.toInt()
                        },
                    ) {
                        PlayerAction(
                            description = stringResource(Res.string.compose_player_more_actions),
                            onClick = {
                                menuOpen = !menuOpen
                                onInteraction()
                            },
                            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        )
                        if (menuOpen) {
                            val density = LocalDensity.current
                            val layoutDirection = LocalLayoutDirection.current
                            val safe = WindowInsets.safeContent.asPaddingValues()
                            val topInsetPx = with(density) { safe.calculateTopPadding().roundToPx() }
                            val startInsetPx = with(density) { safe.calculateLeftPadding(layoutDirection).roundToPx() }
                            val endInsetPx = with(density) { safe.calculateRightPadding(layoutDirection).roundToPx() }
                            val gapPx = with(density) { 8.dp.roundToPx() }
                            val maxHeight = if (menuAnchorTopPx <= 0) {
                                240.dp
                            } else {
                                with(density) {
                                    toolsMenuMaxHeight(
                                        anchorTop = menuAnchorTopPx.toFloat(),
                                        topInset = topInsetPx.toFloat(),
                                        gap = gapPx.toFloat(),
                                    ).toDp()
                                }
                            }
                            PlayerToolsMenu(
                                actions = menuActions,
                                maxHeight = maxHeight,
                                gapPx = gapPx,
                                insetStartPx = startInsetPx,
                                insetEndPx = endInsetPx,
                                onDismiss = { menuOpen = false },
                                onPick = { action ->
                                    menuOpen = false
                                    onInteraction()
                                    action.onClick()
                                },
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .widthIn(min = 48.dp)
                    .playerLiquidGlass(
                        shape = PlayerGlassCapsule,
                        refractionHeight = 6.dp,
                        refractionAmount = 8.dp,
                    )
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(
                            if (showRemainingTime) Res.string.compose_player_show_elapsed_time else Res.string.compose_player_show_remaining_time,
                        ),
                        onClick = {
                            onRuntimeClick()
                            onInteraction()
                        },
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                PlaybackRuntimeLabel(
                    playbackClock = playbackClock,
                    scrubbingPositionMs = scrubbingPositionMs,
                    displayedPositionMs = displayedPositionMs,
                    durationMs = playbackSnapshot.durationMs,
                    showRemainingTime = showRemainingTime,
                    metrics = metrics,
                )
            }
        }
    }
}

@Composable
private fun PlaybackRuntimeLabel(
    playbackClock: PlayerPlaybackClock?,
    scrubbingPositionMs: Long?,
    displayedPositionMs: Long,
    durationMs: Long,
    showRemainingTime: Boolean,
    metrics: PlayerLayoutMetrics,
) {
    val positionMs = if (playbackClock != null) {
        scrubbingPositionMs ?: playbackClock.positionMs
    } else {
        displayedPositionMs
    }
    Text(
        text = formatPlaybackRuntime(positionMs, durationMs, showRemainingTime),
        style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontSize = (metrics.timeSize.value + 2).sp),
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
    )
}

@Composable
private fun PlayerAction(
    description: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    painter: Painter? = null,
    iconSize: Dp = 24.dp,
    tint: Color = Color.White,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        if (painter != null) {
            Icon(painter, description, tint = tint, modifier = Modifier.size(iconSize))
        } else if (icon != null) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

internal val PlayerGlassCapsule = RoundedCornerShape(percent = 50)

@Composable
private fun PlayerToolsMenu(
    actions: List<PlayerControlAction>,
    maxHeight: Dp,
    gapPx: Int,
    insetStartPx: Int,
    insetEndPx: Int,
    onDismiss: () -> Unit,
    onPick: (PlayerControlAction) -> Unit,
) {
    val positionProvider = remember(gapPx, insetStartPx, insetEndPx) {
        ToolsMenuPositionProvider(gapPx, insetStartPx, insetEndPx)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(16.dp)
        val scroll = rememberScrollState()
        val surface = PlayerToolSurface.copy(alpha = 0.96f)
        Box(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 280.dp)
                .heightIn(max = maxHeight)
                .shadow(16.dp, shape)
                .clip(shape)
                .background(surface)
                .border(1.dp, PlayerToolEdge, shape),
        ) {
            Column(Modifier.verticalScroll(scroll)) {
                actions.forEach { action ->
                    val marked = action.icon == Icons.Rounded.Flag
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onPick(action) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val tint = if (marked) MaterialTheme.nuvio.colors.accent else Color.White
                        if (action.painter != null) {
                            Icon(action.painter, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        } else if (action.icon != null) {
                            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            text = action.description,
                            color = tint,
                            fontSize = 16.sp,
                            fontWeight = if (marked) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (scroll.canScrollBackward) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(Brush.verticalGradient(listOf(surface, Color.Transparent))),
                )
            }
            if (scroll.canScrollForward) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, surface))),
                )
            }
        }
    }
}

private class ToolsMenuPositionProvider(
    private val gapPx: Int,
    private val insetStartPx: Int,
    private val insetEndPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = toolsMenuLeft(
            anchorRight = anchorBounds.right,
            menuWidth = popupContentSize.width,
            windowWidth = windowSize.width,
            insetStart = insetStartPx,
            insetEnd = insetEndPx,
        )
        val above = anchorBounds.top - gapPx - popupContentSize.height
        val y = if (above >= 0) above else (anchorBounds.bottom + gapPx)
        return IntOffset(x, y)
    }
}

private data class PlayerControlAction(
    val description: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val painter: Painter? = null,
    val iconSize: Dp = 24.dp,
    val popup: (@Composable () -> Unit)? = null,
)
