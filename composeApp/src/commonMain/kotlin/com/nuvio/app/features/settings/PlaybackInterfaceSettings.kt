package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.LegacyPlayerButtonLayouts
import com.nuvio.app.features.player.PlayerSkipSecondsOptions
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.formatPlaybackSpeedLabel
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.features.player.PlayerSettingsUiState

@Composable
internal fun PlaybackInterfaceSettings(
    isTablet: Boolean,
    settings: PlayerSettingsUiState,
) {
    var showHoldToSpeedValueDialog by remember { mutableStateOf(false) }
    var showLoadingScreenDialog by remember { mutableStateOf(false) }
    var showTouchGesturesDialog by remember { mutableStateOf(false) }
    var skipDirectionDialog by remember { mutableStateOf<Boolean?>(null) } // true = forward
    var showToolbarLayoutDialog by remember { mutableStateOf(false) }
    var showLegacyBottomButtonsDialog by remember { mutableStateOf(false) }
    var showLegacyTopButtonsDialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(if (isTablet) 18.dp else 12.dp)) {
        SettingsSection(
            title = stringResource(Res.string.settings_playback_page_interface),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_legacy_layout),
                    description = stringResource(Res.string.settings_playback_legacy_layout_description),
                    checked = settings.useLegacyPlayerLayout,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setUseLegacyPlayerLayout,
                )
                if (!settings.useLegacyPlayerLayout) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_toolbar_layout),
                        description = stringResource(Res.string.settings_playback_toolbar_layout_description),
                        isTablet = isTablet,
                        onClick = { showToolbarLayoutDialog = true },
                    )
                } else {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_legacy_top_buttons),
                        description = stringResource(Res.string.settings_playback_legacy_top_buttons_description),
                        isTablet = isTablet,
                        onClick = { showLegacyTopButtonsDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_playback_legacy_bottom_buttons),
                        description = stringResource(Res.string.settings_playback_legacy_bottom_buttons_description),
                        isTablet = isTablet,
                        onClick = { showLegacyBottomButtonsDialog = true },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                val loadingScreen = loadingScreenChoice(settings.showLoadingOverlay, settings.showPlayerLoadingStatus)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_loading_screen),
                    description = loadingScreenChoiceLabel(loadingScreen),
                    isTablet = isTablet,
                    onClick = { showLoadingScreenDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_exo_chapters),
                    description = stringResource(Res.string.settings_playback_exo_chapters_description),
                    checked = settings.androidExoChaptersEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setAndroidExoChaptersEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_pause_overlay),
                    description = stringResource(Res.string.settings_playback_pause_overlay_description),
                    checked = settings.pauseOverlayEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setPauseOverlayEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_parental_guide),
                    description = stringResource(Res.string.settings_playback_parental_guide_description),
                    checked = settings.showParentalGuide,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowParentalGuide,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_movie_recommendations),
                    description = stringResource(Res.string.settings_playback_movie_recommendations_description),
                    checked = settings.movieRecommendationsEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setMovieRecommendationsEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_mini_player),
                    description = stringResource(Res.string.settings_playback_mini_player_description),
                    checked = settings.miniPlayerEnabled,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setMiniPlayerEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_private_viewing),
                    description = stringResource(Res.string.settings_playback_private_viewing_description),
                    checked = settings.privateViewingEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setPrivateViewingEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_playback_seek_preview),
                    description = stringResource(Res.string.settings_playback_seek_preview_description),
                    checked = settings.seekPreviewEnabled,
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setSeekPreviewEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                val touchGestures = touchGestureChoice(settings.touchGesturesEnabled, settings.swipeToSeekEnabled)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_touch_gestures),
                    description = touchGestureChoiceLabel(touchGestures),
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onClick = { showTouchGesturesDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                val holdSpeed = holdSpeedChoice(settings.holdToSpeedEnabled, settings.holdToSpeedValue)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_hold_to_speed),
                    description = holdSpeedChoiceLabel(holdSpeed),
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onClick = { showHoldToSpeedValueDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_skip_backward),
                    description = stringResource(
                        Res.string.settings_playback_skip_seconds_value,
                        settings.skipBackwardSeconds,
                    ),
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onClick = { skipDirectionDialog = false },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_playback_skip_forward),
                    description = stringResource(
                        Res.string.settings_playback_skip_seconds_value,
                        settings.skipForwardSeconds,
                    ),
                    enabled = !settings.externalPlayerEnabled,
                    isTablet = isTablet,
                    onClick = { skipDirectionDialog = true },
                )
            }
        }
    }

    if (showToolbarLayoutDialog) {
        PlayerToolbarSettingsDialog(
            layout = settings.toolbarLayout,
            onLayoutChange = PlayerSettingsRepository::setToolbarLayout,
            onDismiss = { showToolbarLayoutDialog = false },
        )
    }

    if (showLegacyTopButtonsDialog) {
        LegacyPlayerButtonsSettingsDialog(
            title = stringResource(Res.string.settings_playback_legacy_top_buttons),
            layout = settings.legacyTopButtons,
            defaultLayout = LegacyPlayerButtonLayouts.DefaultTop,
            label = { it.labelRes },
            onLayoutChange = PlayerSettingsRepository::setLegacyTopButtons,
            onDismiss = { showLegacyTopButtonsDialog = false },
        )
    }

    if (showLegacyBottomButtonsDialog) {
        LegacyPlayerButtonsSettingsDialog(
            title = stringResource(Res.string.settings_playback_legacy_bottom_buttons),
            layout = settings.legacyBottomButtons,
            defaultLayout = LegacyPlayerButtonLayouts.DefaultBottom,
            label = { it.labelRes },
            onLayoutChange = PlayerSettingsRepository::setLegacyBottomButtons,
            onDismiss = { showLegacyBottomButtonsDialog = false },
        )
    }

    skipDirectionDialog?.let { forward ->
        SkipSecondsDialog(
            title = stringResource(
                if (forward) Res.string.settings_playback_skip_forward else Res.string.settings_playback_skip_backward,
            ),
            selectedSeconds = if (forward) settings.skipForwardSeconds else settings.skipBackwardSeconds,
            onSelected = { seconds ->
                if (forward) {
                    PlayerSettingsRepository.setSkipForwardSeconds(seconds)
                } else {
                    PlayerSettingsRepository.setSkipBackwardSeconds(seconds)
                }
                skipDirectionDialog = null
            },
            onDismiss = { skipDirectionDialog = null },
        )
    }

    if (showHoldToSpeedValueDialog) {
        HoldToSpeedValueDialog(
            selected = holdSpeedChoice(settings.holdToSpeedEnabled, settings.holdToSpeedValue),
            onChoice = { choice ->
                val stored = holdSpeedStored(choice, settings.holdToSpeedValue)
                PlayerSettingsRepository.setHoldToSpeedValue(stored.speed)
                PlayerSettingsRepository.setHoldToSpeedEnabled(stored.enabled)
                showHoldToSpeedValueDialog = false
            },
            onDismiss = { showHoldToSpeedValueDialog = false },
        )
    }
    if (showLoadingScreenDialog) {
        LoadingScreenDialog(
            selected = loadingScreenChoice(settings.showLoadingOverlay, settings.showPlayerLoadingStatus),
            onChoice = { choice ->
                val stored = loadingScreenStored(
                    choice,
                    LoadingScreenStored(settings.showLoadingOverlay, settings.showPlayerLoadingStatus),
                )
                PlayerSettingsRepository.setShowLoadingOverlay(stored.showOverlay)
                PlayerSettingsRepository.setShowPlayerLoadingStatus(stored.showStatus)
                showLoadingScreenDialog = false
            },
            onDismiss = { showLoadingScreenDialog = false },
        )
    }
    if (showTouchGesturesDialog) {
        TouchGesturesDialog(
            selected = touchGestureChoice(settings.touchGesturesEnabled, settings.swipeToSeekEnabled),
            onChoice = { choice ->
                val stored = touchGestureStored(choice, settings.swipeToSeekEnabled)
                PlayerSettingsRepository.setSwipeToSeekEnabled(stored.swipeToSeekEnabled)
                PlayerSettingsRepository.setTouchGesturesEnabled(stored.gesturesEnabled)
                showTouchGesturesDialog = false
            },
            onDismiss = { showTouchGesturesDialog = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HoldToSpeedValueDialog(
    selected: HoldSpeedChoice,
    onChoice: (HoldSpeedChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_hold_to_speed),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DialogOption(
                text = stringResource(Res.string.compose_action_off),
                selected = selected is HoldSpeedChoice.Off,
                onClick = { onChoice(HoldSpeedChoice.Off) },
            )
            options.forEach { speed ->
                DialogOption(
                    text = formatPlaybackSpeedLabel(speed),
                    selected = selected == HoldSpeedChoice.Speed(speed),
                    onClick = { onChoice(HoldSpeedChoice.Speed(speed)) },
                )
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun SkipSecondsDialog(
    title: String,
    selectedSeconds: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
        message = stringResource(Res.string.settings_playback_skip_description),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerSkipSecondsOptions.forEach { seconds ->
                DialogOption(
                    text = stringResource(Res.string.settings_playback_skip_seconds_value, seconds),
                    selected = seconds == selectedSeconds,
                    onClick = { onSelected(seconds) },
                )
            }
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun loadingScreenChoiceLabel(choice: LoadingScreenChoice): String = when (choice) {
    LoadingScreenChoice.Off -> stringResource(Res.string.compose_action_off)
    LoadingScreenChoice.Plain -> stringResource(Res.string.settings_playback_loading_screen_plain)
    LoadingScreenChoice.WithStatus -> stringResource(Res.string.settings_playback_loading_screen_with_status)
}

@Composable
private fun touchGestureChoiceLabel(choice: TouchGestureChoice): String = when (choice) {
    TouchGestureChoice.Off -> stringResource(Res.string.compose_action_off)
    TouchGestureChoice.NoSwipeSeek -> stringResource(Res.string.settings_playback_touch_gestures_no_swipe)
    TouchGestureChoice.All -> stringResource(Res.string.settings_playback_touch_gestures_all)
}

@Composable
private fun holdSpeedChoiceLabel(choice: HoldSpeedChoice): String = when (choice) {
    HoldSpeedChoice.Off -> stringResource(Res.string.compose_action_off)
    is HoldSpeedChoice.Speed -> formatPlaybackSpeedLabel(choice.speed)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LoadingScreenDialog(
    selected: LoadingScreenChoice,
    onChoice: (LoadingScreenChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = stringResource(Res.string.settings_playback_loading_screen),
        options = LoadingScreenChoice.entries,
        selected = selected,
        label = { loadingScreenChoiceLabel(it) },
        onSelect = onChoice,
        onDismiss = onDismiss,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TouchGesturesDialog(
    selected: TouchGestureChoice,
    onChoice: (TouchGestureChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = stringResource(Res.string.settings_playback_touch_gestures),
        options = TouchGestureChoice.entries,
        selected = selected,
        label = { touchGestureChoiceLabel(it) },
        onSelect = onChoice,
        onDismiss = onDismiss,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                DialogOption(
                    text = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_done),
                onClick = onDismiss,
            )
        }
    }
}
