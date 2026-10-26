package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nuvio.app.features.autosync.AutoSyncedChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.addon_title
import nuvio.composeapp.generated.resources.compose_player_built_in
import nuvio.composeapp.generated.resources.compose_player_fetch_subtitles
import nuvio.composeapp.generated.resources.player_tool_tab_language
import nuvio.composeapp.generated.resources.player_tool_tab_track
import nuvio.composeapp.generated.resources.compose_player_none
import nuvio.composeapp.generated.resources.compose_player_style
import nuvio.composeapp.generated.resources.compose_player_subtitle_delay
import nuvio.composeapp.generated.resources.compose_player_subtitles
import nuvio.composeapp.generated.resources.settings_playback_option_forced
import nuvio.composeapp.generated.resources.subtitle_language_unknown
import org.jetbrains.compose.resources.stringResource

@Composable
fun SubtitleModal(
    visible: Boolean,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    addonSubtitles: List<AddonSubtitle>,
    selectedAddonSubtitleId: String?,
    isLoadingAddonSubtitles: Boolean,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    subtitleStyle: SubtitleStyleState,
    subtitleDelayMs: Int,
    selectedAddonSubtitle: AddonSubtitle?,
    subtitleAutoSyncState: SubtitleAutoSyncUiState,
    onBuiltInTrackSelected: (Int) -> Unit,
    onAddonSubtitleSelected: (AddonSubtitle) -> Unit,
    onFetchAddonSubtitles: () -> Unit,
    onStyleChanged: (SubtitleStyleState) -> Unit,
    onSubtitleDelayChanged: (Int) -> Unit,
    onSubtitleDelayReset: () -> Unit,
    onSyncByEarClick: () -> Unit,
    onAutoSyncCapture: () -> Unit,
    onAutoSyncAutomatic: () -> Unit,
    onAutoSyncCueSelected: (SubtitleSyncCue) -> Unit,
    onAutoSyncReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveSelectedAddonSubtitle = selectedAddonSubtitle
        ?: addonSubtitles.findSelectedAddon(selectedAddonSubtitleId)
    val playbackLanguageKey = selectedSubtitleLanguageKey(
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        selectedAddonSubtitle = effectiveSelectedAddonSubtitle,
    )
    val playbackOptionId = selectedSubtitleOptionId(
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex,
        selectedAddonSubtitle = effectiveSelectedAddonSubtitle,
    )
    val subtitleStructureKey = subtitleTracksStructureKey(subtitleTracks)
    val addonStructureKey = addonSubtitlesStructureKey(addonSubtitles)
    val languageItems = remember(
        subtitleStructureKey,
        addonStructureKey,
        preferredSubtitleLanguage,
        secondaryPreferredSubtitleLanguage,
        subtitleStyle.showOnlyPreferredLanguages,
        playbackLanguageKey,
    ) {
        buildSubtitleLanguageItems(
            subtitleTracks = subtitleTracks,
            addonSubtitles = addonSubtitles,
            preferredLanguage = preferredSubtitleLanguage,
            secondaryPreferredLanguage = secondaryPreferredSubtitleLanguage,
            showOnlyPreferredLanguages = subtitleStyle.showOnlyPreferredLanguages,
            selectedLanguageKey = playbackLanguageKey,
        )
    }
    var activeLanguageKey by remember(visible) {
        mutableStateOf(
            playbackLanguageKey.takeIf { key -> languageItems.any { it.key == key } }
                ?: languageItems.firstOrNull { it.key != SubtitleOffLanguageKey }?.key
                ?: SubtitleOffLanguageKey,
        )
    }
    var pendingOptionId by remember(visible) { mutableStateOf<String?>(playbackOptionId) }
    val options = remember(activeLanguageKey, subtitleStructureKey, addonStructureKey) {
        buildSubtitleSelectionOptions(activeLanguageKey, subtitleTracks, addonSubtitles)
    }
    val keyedOptions = remember(options) { options.withDuplicateSafeLazyKeys { it.id } }
    val selectedOptionId = pendingOptionId ?: playbackOptionId
    val languageListState = rememberLazyListState()
    val optionsListState = rememberLazyListState()
    val styleVisible = activeLanguageKey != SubtitleOffLanguageKey &&
        selectedOptionId != null && options.any { it.id == selectedOptionId }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val languageIndex = languageItems.indexOfFirst { it.key == activeLanguageKey }
        if (languageIndex >= 0) {
            languageListState.scrollItemIntoViewIfNeeded(languageIndex)
        }
        val optionId = selectedOptionId ?: return@LaunchedEffect
        val optionIndex = options.indexOfFirst { it.id == optionId }
        if (optionIndex >= 0) {
            optionsListState.scrollItemIntoViewIfNeeded(optionIndex)
        }
    }

    LaunchedEffect(languageItems) {
        if (languageItems.none { it.key == activeLanguageKey }) {
            activeLanguageKey = playbackLanguageKey.takeIf { key -> languageItems.any { it.key == key } }
                ?: languageItems.firstOrNull { it.key != SubtitleOffLanguageKey }?.key
                ?: SubtitleOffLanguageKey
        }
    }

    LaunchedEffect(playbackLanguageKey, playbackOptionId) {
        if (playbackOptionId != null || playbackLanguageKey == SubtitleOffLanguageKey) {
            activeLanguageKey = playbackLanguageKey
            pendingOptionId = playbackOptionId
        }
    }

    var page by remember(visible) { mutableStateOf(SubtitleToolPage.Language) }
    val styleTitle = stringResource(Res.string.compose_player_style)
    val subtitlesTitle = stringResource(Res.string.compose_player_subtitles)
    val delayLabel = stringResource(Res.string.compose_player_subtitle_delay)

    PlayerToolPanel(
        visible = visible,
        title = if (page == SubtitleToolPage.Style) styleTitle else subtitlesTitle,
        onBack = if (page == SubtitleToolPage.Style) {
            { page = SubtitleToolPage.Track }
        } else {
            null
        },
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        if (page == SubtitleToolPage.Style) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SubtitleStylePanel(
                    style = subtitleStyle,
                    subtitleDelayMs = subtitleDelayMs,
                    selectedAddonSubtitle = effectiveSelectedAddonSubtitle,
                    subtitleAutoSyncState = subtitleAutoSyncState,
                    isCompact = true,
                    showHeader = false,
                    includeDelay = false,
                    onStyleChanged = onStyleChanged,
                    onSubtitleDelayChanged = onSubtitleDelayChanged,
                    onSubtitleDelayReset = onSubtitleDelayReset,
                    onSyncByEarClick = onSyncByEarClick,
                    onAutoSyncAutomatic = onAutoSyncAutomatic,
                    onAutoSyncCapture = onAutoSyncCapture,
                    onAutoSyncCueSelected = onAutoSyncCueSelected,
                    onAutoSyncReload = onAutoSyncReload,
                )
            }
        } else {
            SubtitleToolTabs(
                selected = page,
                onSelect = { page = it },
            )
            when (page) {
                SubtitleToolPage.Language -> {
                    LazyColumn(
                        state = languageListState,
                        modifier = Modifier.weight(1f),
                    ) {
                        items(languageItems, key = { it.key }) { item ->
                            SubtitleLanguageRow(
                                item = item,
                                selected = item.key == activeLanguageKey,
                                onClick = {
                                    activeLanguageKey = item.key
                                    val availableOptions = buildSubtitleSelectionOptions(
                                        item.key,
                                        subtitleTracks,
                                        addonSubtitles,
                                    )
                                    pendingOptionId = playbackOptionId?.takeIf { id ->
                                        availableOptions.any { it.id == id }
                                    }
                                    if (item.key == SubtitleOffLanguageKey) {
                                        onBuiltInTrackSelected(-1)
                                    } else {
                                        page = SubtitleToolPage.Track
                                    }
                                },
                            )
                        }
                    }
                }

                SubtitleToolPage.Track -> {
                    when {
                        options.isEmpty() -> {
                            when (
                                subtitleOptionsRailEmptyContent(
                                    selectedLanguageKey = activeLanguageKey,
                                    hasAvailableLanguages = languageItems.size > 1,
                                    isLoadingAddonSubtitles = isLoadingAddonSubtitles,
                                )
                            ) {
                                SubtitleOptionsRailEmptyContent.NONE -> {
                                    SubtitleRailEmptyState(
                                        text = stringResource(Res.string.compose_player_none),
                                    )
                                }

                                SubtitleOptionsRailEmptyContent.LOADING -> {
                                    PlayerModalLoading(modifier = Modifier.padding(vertical = 24.dp))
                                }

                                SubtitleOptionsRailEmptyContent.FETCH -> {
                                    SubtitleRailEmptyState(
                                        text = stringResource(Res.string.compose_player_fetch_subtitles),
                                        onClick = onFetchAddonSubtitles,
                                    )
                                }
                            }
                        }

                        else -> {
                            LazyColumn(
                                state = optionsListState,
                                modifier = Modifier.weight(1f),
                            ) {
                                items(keyedOptions, key = { it.lazyKey }) { keyedOption ->
                                    val option = keyedOption.value
                                    SubtitleOptionRow(
                                        option = option,
                                        selected = option.id == selectedOptionId,
                                        onClick = {
                                            pendingOptionId = option.id
                                            when (option) {
                                                is SubtitleSelectionOption.BuiltIn -> {
                                                    onBuiltInTrackSelected(option.track.index)
                                                }

                                                is SubtitleSelectionOption.Addon -> {
                                                    onAddonSubtitleSelected(option.subtitle)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                SubtitleToolPage.Style -> Unit
            }
            if (styleVisible) {
                PlayerToolRow(
                    label = styleTitle,
                    selected = false,
                    trailing = "›",
                    onClick = { page = SubtitleToolPage.Style },
                )
                SubtitleDelayRow(
                    label = delayLabel,
                    value = formatSubtitleDelay(subtitleDelayMs),
                    onDecrease = {
                        onSubtitleDelayChanged(
                            (subtitleDelayMs - SUBTITLE_DELAY_STEP_MS).coerceAtLeast(SUBTITLE_DELAY_MIN_MS),
                        )
                    },
                    onIncrease = {
                        onSubtitleDelayChanged(
                            (subtitleDelayMs + SUBTITLE_DELAY_STEP_MS).coerceAtMost(SUBTITLE_DELAY_MAX_MS),
                        )
                    },
                )
            }
        }
    }
}

private enum class SubtitleToolPage {
    Language,
    Track,
    Style,
}

@Composable
private fun SubtitleToolTabs(
    selected: SubtitleToolPage,
    onSelect: (SubtitleToolPage) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val tabs = listOf(
        SubtitleToolPage.Language to stringResource(Res.string.player_tool_tab_language),
        SubtitleToolPage.Track to stringResource(Res.string.player_tool_tab_track),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { (tab, label) ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) tokens.colors.accent else PlayerGlassFallback)
                    .clickable { onSelect(tab) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) tokens.colors.onAccent else Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SubtitleDelayRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PlayerToolPanelRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SubtitleDelayStep(icon = Icons.Rounded.Remove, onClick = onDecrease)
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 8.dp),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
        )
        SubtitleDelayStep(icon = Icons.Rounded.Add, onClick = onIncrease)
    }
}

@Composable
private fun SubtitleDelayStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun SubtitleLanguageRow(
    item: SubtitleLanguageItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val label = when (item.key) {
        SubtitleOffLanguageKey -> stringResource(Res.string.compose_player_none)
        SubtitleUnknownLanguageKey -> stringResource(Res.string.subtitle_language_unknown)
        else -> languageLabelForCode(item.key)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PlayerToolPanelRowHeight)
            .clip(RoundedCornerShape(PlayerToolPanelRowRadius))
            .background(if (selected) tokens.colors.accent else Color.Transparent)
            .clickableIncludingFlingStop(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            color = if (selected) tokens.colors.onAccent else Color.White,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.count > 0) {
            Text(
                text = item.count.toString(),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) Color.White.copy(alpha = 0.18f)
                        else tokens.colors.accent.copy(alpha = 0.85f),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                color = tokens.colors.onAccent,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SubtitleOptionRow(
    option: SubtitleSelectionOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sourceLabel: String
    val title: String
    val metadata: String?

    when (option) {
        is SubtitleSelectionOption.BuiltIn -> {
            sourceLabel = stringResource(Res.string.compose_player_built_in)
            title = localizedTrackDisplayName(
                option.track.label,
                option.track.language,
                option.track.index,
            )
            metadata = if (option.track.isForced) {
                stringResource(Res.string.settings_playback_option_forced)
            } else {
                null
            }
        }

        is SubtitleSelectionOption.Addon -> {
            sourceLabel = option.subtitle.addonName ?: stringResource(Res.string.addon_title)
            title = languageLabelForCode(option.subtitle.language)
            metadata = option.subtitle.display.takeIf { it.isNotBlank() && it != title }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PlayerToolPanelRowHeight)
            .clip(RoundedCornerShape(PlayerToolPanelRowRadius))
            .background(if (selected) tokens.colors.accent else Color.Transparent)
            .clickableIncludingFlingStop(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SubtitleSourceChip(label = sourceLabel, selected = selected)
                (option as? SubtitleSelectionOption.Addon)?.let {
                    AutoSyncedChip(it.subtitle.url, selected) // AutoSync hook
                }
            }
            Text(
                text = title,
                color = if (selected) tokens.colors.onAccent else Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            metadata?.let {
                Text(
                    text = it,
                    color = if (selected) tokens.colors.onAccent.copy(alpha = 0.72f) else tokens.colors.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.onAccent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun SubtitleSourceChip(
    label: String,
    selected: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(999.dp)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) tokens.colors.onAccent.copy(alpha = 0.14f)
                else Color.White.copy(alpha = 0.08f),
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, tokens.colors.onAccent.copy(alpha = 0.22f), shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            color = if (selected) tokens.colors.onAccent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SubtitleRailEmptyState(
    text: String,
    onClick: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClick != null) {
            Icon(
                imageVector = Icons.Rounded.CloudDownload,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = text,
            color = tokens.colors.textMuted,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private suspend fun LazyListState.scrollItemIntoViewIfNeeded(targetIndex: Int) {
    if (targetIndex < 0) return
    if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) return
    scrollToItem(targetIndex)
}
