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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.party.partyMediaKey
import com.nuvio.app.features.streams.SharedStream
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.isIos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.compose_player_fetch_subtitles
import nuvio.composeapp.generated.resources.compose_player_no_audio_tracks_available
import nuvio.composeapp.generated.resources.compose_player_no_episodes_available
import nuvio.composeapp.generated.resources.compose_player_style
import nuvio.composeapp.generated.resources.episodes_season
import nuvio.composeapp.generated.resources.episodes_specials
import nuvio.composeapp.generated.resources.player_tools_aired
import nuvio.composeapp.generated.resources.player_tools_aspect
import nuvio.composeapp.generated.resources.player_tools_audio
import nuvio.composeapp.generated.resources.player_tools_cached
import nuvio.composeapp.generated.resources.player_tools_episode
import nuvio.composeapp.generated.resources.player_tools_file
import nuvio.composeapp.generated.resources.player_tools_min_left
import nuvio.composeapp.generated.resources.player_tools_off
import nuvio.composeapp.generated.resources.party_shared_by
import nuvio.composeapp.generated.resources.player_tools_playing
import nuvio.composeapp.generated.resources.player_tools_reset_video
import nuvio.composeapp.generated.resources.player_tools_resolution
import nuvio.composeapp.generated.resources.player_tools_show
import nuvio.composeapp.generated.resources.player_tools_speed
import nuvio.composeapp.generated.resources.player_tools_sync
import nuvio.composeapp.generated.resources.player_tools_tone_map
import nuvio.composeapp.generated.resources.player_tools_tracks
import nuvio.composeapp.generated.resources.player_tools_up_next
import nuvio.composeapp.generated.resources.player_tools_video
import nuvio.composeapp.generated.resources.player_video_settings_brightness
import nuvio.composeapp.generated.resources.player_video_settings_contrast
import nuvio.composeapp.generated.resources.player_video_settings_deband
import nuvio.composeapp.generated.resources.player_video_settings_gamma
import nuvio.composeapp.generated.resources.player_video_settings_hdr_peak_detection
import nuvio.composeapp.generated.resources.player_video_settings_interpolation
import nuvio.composeapp.generated.resources.player_video_settings_output_preset
import nuvio.composeapp.generated.resources.player_video_settings_saturation
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal enum class PlayerToolTab(val label: String, val icon: ImageVector) {
    Subs("Subs", Icons.Rounded.ClosedCaption),
    Audio("Audio", Icons.Rounded.GraphicEq),
    Sources("Srcs", Icons.Rounded.Download),
    Episodes("Eps", Icons.Rounded.GridView),
    Info("Info", Icons.Rounded.Info),
    Video("Video", Icons.Rounded.Tune),
}

@Composable
internal fun PlayerToolsPanel(
    tab: PlayerToolTab,
    tabs: List<PlayerToolTab>,
    onTab: (PlayerToolTab) -> Unit,
    onDismiss: () -> Unit,
    endInset: Dp,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleIndex: Int,
    addonSubtitles: List<AddonSubtitle>,
    selectedAddonSubtitle: AddonSubtitle?,
    isLoadingAddonSubtitles: Boolean,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    subtitleStyle: SubtitleStyleState,
    subtitleDelayMs: Int,
    subtitleAutoSyncState: SubtitleAutoSyncUiState,
    onBuiltInSubtitle: (Int) -> Unit,
    onAddonSubtitle: (AddonSubtitle) -> Unit,
    onFetchAddonSubtitles: () -> Unit,
    onStyleChanged: (SubtitleStyleState) -> Unit,
    onDelayChanged: (Int) -> Unit,
    onDelayReset: () -> Unit,
    onSync: () -> Unit,
    onAutoSyncAutomatic: () -> Unit,
    onAutoSyncCapture: () -> Unit,
    onAutoSyncCue: (SubtitleSyncCue) -> Unit,
    onAutoSyncReload: () -> Unit,
    audioTracks: List<AudioTrack>,
    selectedAudioIndex: Int,
    onAudio: (Int) -> Unit,
    streams: StreamsUiState,
    currentStreamUrl: String?,
    currentStreamName: String?,
    onSourceFilter: (String?) -> Unit,
    onSource: (StreamItem) -> Unit,
    onReloadSources: () -> Unit,
    showEpisodes: Boolean,
    episodes: List<MetaVideo>,
    parentMetaType: String,
    parentMetaId: String,
    currentSeason: Int?,
    currentEpisode: Int?,
    progressByVideoId: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    onEpisode: (MetaVideo) -> Unit,
    episodeStreams: EpisodeStreamsPanelState,
    onEpisodeStreamFilter: (String?) -> Unit,
    onEpisodeStream: (StreamItem, MetaVideo) -> Unit,
    onBackToEpisodes: () -> Unit,
    onReloadEpisodeStreams: () -> Unit,
    episodesLoading: Boolean,
    episodesFailed: Boolean,
    onRetryEpisodes: () -> Unit,
    showTitle: String,
    episodeLine: String?,
    aired: String?,
    mediaInfoJson: String,
    selectedQuality: PlayerQualityVariant?,
    selectedQualityIsAuto: Boolean,
    audioLine: String?,
    fileName: String?,
    resizeMode: PlayerResizeMode,
    onResize: (PlayerResizeMode) -> Unit,
    playbackSpeed: Float,
    onSpeed: (Float) -> Unit,
    videoSettings: PlayerSettingsUiState,
    onVideoSettingsChanged: () -> Unit,
    onResetVideo: () -> Unit,
    styleOpen: Boolean,
    onStyleOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = PlayerToolShape
    val safePadding = WindowInsets.safeContent.asPaddingValues()
    Box(
        modifier = modifier
            .width(PlayerToolPanelWidthDp.dp)
            .fillMaxHeight()
            .shadow(18.dp, shape, clip = false)
            .clip(shape)
            .background(PlayerToolSurface.copy(alpha = 0.88f))
            .border(1.dp, PlayerToolEdge, shape),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = safePadding.calculateTopPadding(),
                    bottom = safePadding.calculateBottomPadding(),
                    end = endInset,
                ),
        ) {
            PlayerToolTabs(tab = tab, tabs = tabs, onTab = {
                onStyleOpenChange(false)
                onTab(it)
            }, onDismiss = onDismiss)
            Box(Modifier.fillMaxWidth().playerToolDivider())
            when (tab) {
                PlayerToolTab.Subs -> if (styleOpen) {
                    StylePage(
                        style = subtitleStyle,
                        delayMs = subtitleDelayMs,
                        addon = selectedAddonSubtitle,
                        autoSync = subtitleAutoSyncState,
                        onStyleChanged = onStyleChanged,
                        onDelayChanged = onDelayChanged,
                        onDelayReset = onDelayReset,
                        onSync = onSync,
                        onAutoSyncAutomatic = onAutoSyncAutomatic,
                        onAutoSyncCapture = onAutoSyncCapture,
                        onAutoSyncCue = onAutoSyncCue,
                        onAutoSyncReload = onAutoSyncReload,
                        onBack = { onStyleOpenChange(false) },
                    )
                } else {
                    SubsPage(
                        tracks = subtitleTracks,
                        selectedIndex = selectedSubtitleIndex,
                        addons = addonSubtitles,
                        selectedAddon = selectedAddonSubtitle,
                        loadingAddons = isLoadingAddonSubtitles,
                        preferred = preferredSubtitleLanguage,
                        secondary = secondaryPreferredSubtitleLanguage,
                        style = subtitleStyle,
                        delayMs = subtitleDelayMs,
                        onBuiltIn = onBuiltInSubtitle,
                        onAddon = onAddonSubtitle,
                        onFetch = onFetchAddonSubtitles,
                        onSync = onSync,
                        onStyle = { onStyleOpenChange(true) },
                    )
                }
                PlayerToolTab.Audio -> AudioPage(audioTracks, selectedAudioIndex, onAudio)
                PlayerToolTab.Sources -> SourcesPage(
                    streams, currentStreamUrl, currentStreamName, onSourceFilter, onSource, onReloadSources,
                )
                PlayerToolTab.Episodes -> if (!showEpisodes) {
                    Box(Modifier.fillMaxSize())
                } else if (episodeStreams.showStreams) {
                    EpisodeStreamsPage(
                        state = episodeStreams,
                        parentMetaType = parentMetaType,
                        parentMetaId = parentMetaId,
                        onFilter = onEpisodeStreamFilter,
                        onStream = onEpisodeStream,
                        onBack = onBackToEpisodes,
                        onReload = onReloadEpisodeStreams,
                    )
                } else {
                    EpisodesPage(
                        episodes = episodes,
                        parentMetaType = parentMetaType,
                        parentMetaId = parentMetaId,
                        currentSeason = currentSeason,
                        currentEpisode = currentEpisode,
                        progress = progressByVideoId,
                        watchedKeys = watchedKeys,
                        loading = episodesLoading,
                        failed = episodesFailed,
                        onEpisode = onEpisode,
                        onRetry = onRetryEpisodes,
                    )
                }
                PlayerToolTab.Info -> InfoPage(
                    showTitle = showTitle,
                    episodeLine = episodeLine,
                    aired = aired,
                    mediaInfoJson = mediaInfoJson,
                    selectedQuality = selectedQuality,
                    selectedQualityIsAuto = selectedQualityIsAuto,
                    audioLine = audioLine,
                    fileName = fileName,
                )
                PlayerToolTab.Video -> VideoPage(
                    resizeMode = resizeMode,
                    onResize = onResize,
                    playbackSpeed = playbackSpeed,
                    onSpeed = onSpeed,
                    settings = videoSettings,
                    onChanged = onVideoSettingsChanged,
                    onReset = onResetVideo,
                )
            }
        }
    }
}

@Composable
private fun PlayerToolTabs(
    tab: PlayerToolTab,
    tabs: List<PlayerToolTab>,
    onTab: (PlayerToolTab) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            val selected = item == tab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clickable { onTab(item) }
                    .background(if (selected) tokens.colors.accent else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (selected) tokens.colors.onAccent else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = item.label,
                    color = if (selected) tokens.colors.onAccent else Color.White,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
        PlayerToolIconButton(
            icon = Icons.Rounded.Close,
            description = stringResource(Res.string.action_close),
            onClick = onDismiss,
        )
    }
}

@Composable
private fun SubsPage(
    tracks: List<SubtitleTrack>,
    selectedIndex: Int,
    addons: List<AddonSubtitle>,
    selectedAddon: AddonSubtitle?,
    loadingAddons: Boolean,
    preferred: String,
    secondary: String?,
    style: SubtitleStyleState,
    delayMs: Int,
    onBuiltIn: (Int) -> Unit,
    onAddon: (AddonSubtitle) -> Unit,
    onFetch: () -> Unit,
    onSync: () -> Unit,
    onStyle: () -> Unit,
) {
    val effectiveAddon = selectedAddon
    val languageKey = selectedSubtitleLanguageKey(tracks, selectedIndex, effectiveAddon)
    val languages = remember(tracks, addons, preferred, secondary, style.showOnlyPreferredLanguages, languageKey) {
        buildSubtitleLanguageItems(
            subtitleTracks = tracks,
            addonSubtitles = addons,
            preferredLanguage = preferred,
            secondaryPreferredLanguage = secondary,
            showOnlyPreferredLanguages = style.showOnlyPreferredLanguages,
            selectedLanguageKey = languageKey,
        )
    }
    var expanded by remember(languageKey) { mutableStateOf(languageKey.takeIf { it != SubtitleOffLanguageKey }) }
    val off = stringResource(Res.string.player_tools_off)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f)) {
            items(languages, key = { it.key }) { item ->
                val isOff = item.key == SubtitleOffLanguageKey
                val label = if (isOff) off else languageLabelForCode(item.key)
                val selected = isOff && languageKey == SubtitleOffLanguageKey
                PlayerToolRow(
                    title = label,
                    selected = selected,
                    onClick = {
                        if (isOff) {
                            expanded = null
                            onBuiltIn(-1)
                        } else {
                            expanded = if (expanded == item.key) null else item.key
                        }
                    },
                    trailing = if (!isOff && item.count > 1) stringResource(Res.string.player_tools_tracks, item.count) else null,
                    showCheck = selected,
                )
                if (expanded == item.key) {
                    val options = buildSubtitleSelectionOptions(item.key, tracks, addons)
                    options.forEach { option ->
                        val selectedOption = when (option) {
                            is SubtitleSelectionOption.BuiltIn ->
                                effectiveAddon == null && option.track.index == selectedIndex
                            is SubtitleSelectionOption.Addon ->
                                option.subtitle.matchesSelection(effectiveAddon?.selectionKey)
                        }
                        val badge = subtitleBadge(option)
                        PlayerToolRow(
                            title = subtitleOptionTitle(label, option),
                            selected = selectedOption,
                            onClick = {
                                when (option) {
                                    is SubtitleSelectionOption.BuiltIn -> onBuiltIn(option.track.index)
                                    is SubtitleSelectionOption.Addon -> onAddon(option.subtitle)
                                }
                            },
                            badge = badge,
                        )
                    }
                }
            }
            item {
                PlayerToolRow(
                    title = stringResource(Res.string.compose_player_fetch_subtitles),
                    selected = false,
                    onClick = onFetch,
                    trailing = if (loadingAddons) "…" else null,
                    showCheck = false,
                )
            }
        }
        Box(Modifier.fillMaxWidth().playerToolDivider())
        PlayerToolRow(
            title = stringResource(Res.string.player_tools_sync),
            selected = false,
            onClick = onSync,
            trailing = formatSubtitleDelayLabel(delayMs),
            showCheck = false,
        )
        PlayerToolRow(
            title = stringResource(Res.string.compose_player_style),
            selected = false,
            onClick = onStyle,
            trailing = subtitleStyleSummary(style),
            showCheck = false,
        )
    }
}

@Composable
private fun StylePage(
    style: SubtitleStyleState,
    delayMs: Int,
    addon: AddonSubtitle?,
    autoSync: SubtitleAutoSyncUiState,
    onStyleChanged: (SubtitleStyleState) -> Unit,
    onDelayChanged: (Int) -> Unit,
    onDelayReset: () -> Unit,
    onSync: () -> Unit,
    onAutoSyncAutomatic: () -> Unit,
    onAutoSyncCapture: () -> Unit,
    onAutoSyncCue: (SubtitleSyncCue) -> Unit,
    onAutoSyncReload: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PlayerToolRow(
            title = stringResource(Res.string.action_back),
            selected = false,
            onClick = onBack,
            showCheck = false,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SubtitleStylePanel(
                style = style,
                subtitleDelayMs = delayMs,
                selectedAddonSubtitle = addon,
                subtitleAutoSyncState = autoSync,
                isCompact = true,
                showHeader = false,
                onStyleChanged = onStyleChanged,
                onSubtitleDelayChanged = onDelayChanged,
                onSubtitleDelayReset = onDelayReset,
                onSyncByEarClick = onSync,
                onAutoSyncAutomatic = onAutoSyncAutomatic,
                onAutoSyncCapture = onAutoSyncCapture,
                onAutoSyncCueSelected = onAutoSyncCue,
                onAutoSyncReload = onAutoSyncReload,
            )
        }
    }
}

@Composable
private fun AudioPage(
    tracks: List<AudioTrack>,
    selectedIndex: Int,
    onAudio: (Int) -> Unit,
) {
    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.compose_player_no_audio_tracks_available),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = PlayerToolRowText,
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tracks, key = { "${it.index}:${it.id}" }) { track ->
            val title = localizedTrackDisplayName(track.label, track.language, track.index)
            val language = track.language?.takeIf { it.isNotBlank() && it != "und" }?.let { languageLabelForCode(it) }
            PlayerToolRow(
                title = title,
                subtitle = language?.takeIf { it != title },
                selected = track.index == selectedIndex,
                onClick = { onAudio(track.index) },
            )
        }
    }
}

@Composable
private fun SourcesPage(
    streams: StreamsUiState,
    currentUrl: String?,
    currentName: String?,
    onFilter: (String?) -> Unit,
    onSource: (StreamItem) -> Unit,
    onReload: () -> Unit,
) {
    val all = streams.allStreams
    var bucket by remember { mutableStateOf<SourceQualityBucket?>(null) }
    val facts = remember(all) { all.map { it to sourceFactsFor(it) } }
    val shown = facts.filter { bucket == null || it.second.bucket == bucket }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PlayerToolChip("All ${all.size}", bucket == null, onClick = { bucket = null })
            }
            val counts = facts.groupingBy { it.second.bucket }.eachCount()
            listOf(
                SourceQualityBucket.Uhd to "4K",
                SourceQualityBucket.P1080 to "1080p",
                SourceQualityBucket.P720 to "720p",
            ).forEach { (kind, label) ->
                val count = counts[kind] ?: 0
                if (count > 0) {
                    item {
                        PlayerToolChip("$label $count", bucket == kind, onClick = { bucket = kind })
                    }
                }
            }
        }
        PlayerProviderFilterRow(
            streamsUiState = streams,
            onFilterSelected = onFilter,
            onRefresh = onReload,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.first.stableKey() }) { (stream, fact) ->
                val selected = stream.isCurrentPlayerStream(currentUrl, currentName)
                PlayerToolRow(
                    title = stream.streamLabel,
                    subtitle = sourceSubtitle(fact),
                    selected = selected,
                    onClick = { onSource(stream) },
                )
            }
        }
    }
}

@Composable
private fun EpisodesPage(
    episodes: List<MetaVideo>,
    parentMetaType: String,
    parentMetaId: String,
    currentSeason: Int?,
    currentEpisode: Int?,
    progress: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    loading: Boolean,
    failed: Boolean,
    onEpisode: (MetaVideo) -> Unit,
    onRetry: () -> Unit,
) {
    val grouped = remember(episodes) {
        episodes.filter { it.season != null || it.episode != null }
            .groupBy { it.season?.coerceAtLeast(0) ?: 0 }
    }
    val seasons = remember(grouped) {
        grouped.keys.filter { it > 0 }.sorted() + grouped.keys.filter { it == 0 }
    }
    var season by remember(currentSeason, seasons) {
        mutableStateOf(
            when {
                currentSeason != null && currentSeason in seasons -> currentSeason
                seasons.isNotEmpty() -> seasons.first()
                else -> 1
            },
        )
    }
    val rows = grouped[season].orEmpty().sortedBy { it.episode ?: 0 }
    Column(Modifier.fillMaxSize()) {
        if (seasons.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(seasons) { item ->
                    val label = if (item == 0) {
                        stringResource(Res.string.episodes_specials)
                    } else {
                        stringResource(Res.string.episodes_season, item)
                    }
                    PlayerToolChip(label, item == season, onClick = { season = item })
                }
            }
        }
        when {
            loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("…", color = Color.White)
            }
            failed && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PlayerToolTextButton(stringResource(Res.string.action_retry), onRetry)
            }
            rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.compose_player_no_episodes_available),
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            else -> LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { "${it.season}:${it.episode}:${it.id}" }) { episode ->
                    EpisodeToolRow(
                        episode = episode,
                        parentMetaType = parentMetaType,
                        parentMetaId = parentMetaId,
                        currentSeason = currentSeason,
                        currentEpisode = currentEpisode,
                        progress = progress,
                        watchedKeys = watchedKeys,
                        onClick = { onEpisode(episode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeToolRow(
    episode: MetaVideo,
    parentMetaType: String,
    parentMetaId: String,
    currentSeason: Int?,
    currentEpisode: Int?,
    progress: Map<String, WatchProgressEntry>,
    watchedKeys: Set<String>,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val videoId = buildPlaybackVideoId(parentMetaId, episode.season, episode.episode, episode.id)
    val entry = progress[videoId]
    val watched = entry?.isEffectivelyCompleted == true || WatchingState.isEpisodeWatched(
        watchedKeys, parentMetaType, parentMetaId, episode,
    )
    val playing = episode.season == currentSeason && episode.episode == currentEpisode
    val upNext = !playing && !watched && episode.season == currentSeason &&
        currentEpisode != null && episode.episode == currentEpisode + 1
    val code = episode.episode?.let { "E$it" }.orEmpty()
    val detail = buildList {
        if (code.isNotBlank()) add(code)
        when {
            playing -> add(stringResource(Res.string.player_tools_playing))
            upNext -> add(stringResource(Res.string.player_tools_up_next))
        }
        val runtime = episode.runtime
        if (runtime != null && runtime > 0 && !watched) {
            val left = ((1f - (entry?.progressFraction ?: 0f)) * runtime).roundToInt().coerceAtLeast(1)
            add(stringResource(Res.string.player_tools_min_left, left))
        }
    }.joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (playing) tokens.colors.accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            episode.thumbnail?.let { thumb ->
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            val fraction = entry?.progressFraction ?: 0f
            if (fraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(tokens.colors.accent),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.title.ifBlank { code },
                color = if (playing) tokens.colors.onAccent else Color.White,
                fontSize = PlayerToolRowText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    color = if (playing) tokens.colors.onAccent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (watched) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF3DDC84),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EpisodeStreamsPage(
    state: EpisodeStreamsPanelState,
    parentMetaType: String,
    parentMetaId: String,
    onFilter: (String?) -> Unit,
    onStream: (StreamItem, MetaVideo) -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
) {
    val episode = state.selectedEpisode ?: return
    val partyState by PartyRepository.state.collectAsStateWithLifecycle()
    val partyStream by PartyRepository.sharedStream.collectAsStateWithLifecycle()
    val party = partyState.party
    val sharedLabel = stringResource(Res.string.party_shared_by, party?.member(party.hostMemberId)?.displayName.orEmpty())
    val episodeKey = partyMediaKey(parentMetaType, parentMetaId, episode.season, episode.episode)
    val sharedStream = remember(partyStream, sharedLabel, party?.isHost, episodeKey) {
        partyStream
            ?.takeIf { party != null && !party.isHost && it.mediaKey == episodeKey }
            ?.let { SharedStream(label = sharedLabel, stream = it.toStreamItem()) }
    }
    Column(Modifier.fillMaxSize()) {
        PlayerToolRow(
            title = stringResource(Res.string.action_back),
            subtitle = episode.title,
            selected = false,
            onClick = onBack,
            showCheck = false,
        )
        PlayerProviderFilterRow(
            streamsUiState = state.streamsUiState,
            onFilterSelected = onFilter,
            onRefresh = onReload,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        PlayerStreamList(
            streamsUiState = state.streamsUiState,
            onStreamSelected = { onStream(it, episode) },
            modifier = Modifier.weight(1f),
            sharedStream = sharedStream,
        )
    }
}

@Composable
private fun InfoPage(
    showTitle: String,
    episodeLine: String?,
    aired: String?,
    mediaInfoJson: String,
    selectedQuality: PlayerQualityVariant?,
    selectedQualityIsAuto: Boolean,
    audioLine: String?,
    fileName: String?,
) {
    val parsed = remember(mediaInfoJson) {
        runCatching { Json.parseToJsonElement(mediaInfoJson).jsonObject }.getOrNull()
    }
    val width = parsed.intOrSnapshot("videoWidth")
    val height = parsed.intOrSnapshot("videoHeight")
    val codec = parsed?.stringOrEmpty("videoCodec").orEmpty()
    val bitrate = parsed?.intOrZero("videoBitrateKbps") ?: 0
    val hdr = parsed?.stringOrEmpty("hdrFormat").orEmpty()
    val resolution = buildString {
        if (width > 0 && height > 0) append("$width × $height")
        val range = when (hdr) {
            "sdr" -> "SDR"
            "hdr", "hdr10_plus" -> "HDR"
            "dolby_vision" -> "DV"
            "hlg" -> "HLG"
            else -> ""
        }
        if (range.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(range)
        }
    }
    val video = buildString {
        if (codec.isNotBlank()) append(codec)
        if (bitrate > 0) {
            if (isNotEmpty()) append(" · ")
            val mbps = bitrate / 1000.0
            append(if (mbps >= 1.0) "${(mbps * 10).roundToInt() / 10.0} Mbps" else "$bitrate kbps")
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { InfoLine(stringResource(Res.string.player_tools_show), showTitle) }
        episodeLine?.let { item { InfoLine(stringResource(Res.string.player_tools_episode), it) } }
        aired?.takeIf { it.isNotBlank() }?.let {
            item { InfoLine(stringResource(Res.string.player_tools_aired), formatReleaseDateForDisplay(it)) }
        }
        if (resolution.isNotBlank()) item { InfoLine(stringResource(Res.string.player_tools_resolution), resolution) }
        if (video.isNotBlank()) item { InfoLine(stringResource(Res.string.player_tools_video), video) }
        audioLine?.takeIf { it.isNotBlank() }?.let {
            item { InfoLine(stringResource(Res.string.player_tools_audio), it) }
        }
        fileName?.takeIf { it.isNotBlank() }?.let {
            item { InfoLine(stringResource(Res.string.player_tools_file), it) }
        }
        item {
            PlaybackInfoContent(
                parsed = parsed,
                selectedQualityVariant = selectedQuality,
                selectedQualityIsAuto = selectedQualityIsAuto,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp, modifier = Modifier.weight(0.4f))
        Text(
            value,
            color = Color.White,
            fontSize = PlayerToolRowText,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
private fun VideoPage(
    resizeMode: PlayerResizeMode,
    onResize: (PlayerResizeMode) -> Unit,
    playbackSpeed: Float,
    onSpeed: (Float) -> Unit,
    settings: PlayerSettingsUiState,
    onChanged: () -> Unit,
    onReset: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            ChipLine(stringResource(Res.string.player_tools_aspect)) {
                PlayerResizeMode.entries.forEach { mode ->
                    PlayerToolChip(
                        label = stringResource(mode.labelRes),
                        selected = mode == resizeMode,
                        onClick = { onResize(mode) },
                    )
                }
            }
            ChipLine(stringResource(Res.string.player_tools_speed)) {
                PlayerPlaybackSpeedOptions.forEach { speed ->
                    PlayerToolChip(
                        label = formatPlaybackSpeedLabel(speed),
                        selected = kotlin.math.abs(speed - playbackSpeed) < 0.02f,
                        onClick = { onSpeed(speed) },
                    )
                }
            }
            if (isIos) {
                PictureLine(
                    title = stringResource(Res.string.player_video_settings_brightness),
                    value = settings.iosBrightness,
                    onChange = {
                        PlayerSettingsRepository.setIosBrightness(it)
                        onChanged()
                    },
                )
                PictureLine(
                    title = stringResource(Res.string.player_video_settings_contrast),
                    value = settings.iosContrast,
                    onChange = {
                        PlayerSettingsRepository.setIosContrast(it)
                        onChanged()
                    },
                )
                PictureLine(
                    title = stringResource(Res.string.player_video_settings_saturation),
                    value = settings.iosSaturation,
                    onChange = {
                        PlayerSettingsRepository.setIosSaturation(it)
                        onChanged()
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.player_tools_tone_map),
                        color = Color.White,
                        fontSize = PlayerToolRowText,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.iosToneMappingMode != IosToneMappingMode.Clip,
                        onCheckedChange = { on ->
                            PlayerSettingsRepository.setIosToneMappingMode(
                                if (on) IosToneMappingMode.Auto else IosToneMappingMode.Clip,
                            )
                            onChanged()
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = tokens.colors.accent,
                            checkedThumbColor = tokens.colors.onAccent,
                        ),
                    )
                }
                Text(
                    stringResource(Res.string.player_video_settings_output_preset),
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                IosVideoOutputPreset.entries.forEach { preset ->
                    PlayerToolRow(
                        title = preset.localizedLabel(),
                        subtitle = preset.localizedDescription(),
                        selected = settings.iosVideoOutputPreset == preset,
                        onClick = {
                            PlayerSettingsRepository.setIosVideoOutputPreset(preset)
                            onChanged()
                        },
                    )
                }
                PlayerToolRow(
                    title = stringResource(Res.string.player_video_settings_hdr_peak_detection),
                    selected = settings.iosHdrComputePeakEnabled,
                    onClick = {
                        PlayerSettingsRepository.setIosHdrComputePeakEnabled(!settings.iosHdrComputePeakEnabled)
                        onChanged()
                    },
                )
                IosToneMappingMode.entries.forEach { mode ->
                    PlayerToolRow(
                        title = mode.label,
                        selected = settings.iosToneMappingMode == mode,
                        onClick = {
                            PlayerSettingsRepository.setIosToneMappingMode(mode)
                            onChanged()
                        },
                    )
                }
                PlayerToolRow(
                    title = stringResource(Res.string.player_video_settings_deband),
                    selected = settings.iosDebandEnabled,
                    onClick = {
                        PlayerSettingsRepository.setIosDebandEnabled(!settings.iosDebandEnabled)
                        onChanged()
                    },
                )
                PlayerToolRow(
                    title = stringResource(Res.string.player_video_settings_interpolation),
                    selected = settings.iosInterpolationEnabled,
                    onClick = {
                        PlayerSettingsRepository.setIosInterpolationEnabled(!settings.iosInterpolationEnabled)
                        onChanged()
                    },
                )
                PictureLine(
                    title = stringResource(Res.string.player_video_settings_gamma),
                    value = settings.iosGamma,
                    onChange = {
                        PlayerSettingsRepository.setIosGamma(it)
                        onChanged()
                    },
                )
            }
        }
        Box(Modifier.fillMaxWidth().playerToolDivider())
        PlayerToolRow(
            title = stringResource(Res.string.player_tools_reset_video),
            selected = false,
            onClick = onReset,
            showCheck = false,
        )
    }
}

@Composable
private fun ChipLine(title: String, chips: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.padding(end = 4.dp))
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { chips() }
    }
}

@Composable
private fun PictureLine(title: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.width(96.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(-50, 50)) },
            valueRange = -50f..50f,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (value > 0) "+$value" else value.toString(),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 15.sp,
            modifier = Modifier.width(36.dp),
        )
    }
}

private fun subtitleBadge(option: SubtitleSelectionOption): String? = when (option) {
    is SubtitleSelectionOption.BuiltIn -> when {
        option.track.isForced || option.track.label.contains("forced", ignoreCase = true) -> "FORCED"
        option.track.label.contains("sdh", ignoreCase = true) -> "SDH"
        else -> null
    }
    is SubtitleSelectionOption.Addon -> when {
        option.subtitle.display.contains("sdh", ignoreCase = true) -> "SDH"
        option.subtitle.display.contains("forced", ignoreCase = true) -> "FORCED"
        else -> "ADDON"
    }
}

private fun subtitleOptionTitle(language: String, option: SubtitleSelectionOption): String {
    val raw = when (option) {
        is SubtitleSelectionOption.BuiltIn -> option.track.label
        is SubtitleSelectionOption.Addon -> option.subtitle.display.ifBlank { option.subtitle.addonName.orEmpty() }
    }
    val variant = when (subtitleBadge(option)) {
        "SDH" -> "SDH"
        "FORCED" -> "Forced"
        "ADDON" -> raw.ifBlank { "OpenSubtitles" }
        else -> raw
    }
    return if (variant.isBlank() || variant.equals(language, ignoreCase = true)) language else "$language · $variant"
}

private fun subtitleStyleSummary(style: SubtitleStyleState): String {
    val size = when {
        style.fontSizeSp >= 24 -> "Large"
        style.fontSizeSp >= 16 -> "Medium"
        else -> "Small"
    }
    val color = style.textColor
    val name = when {
        color.red > 0.85f && color.green > 0.75f && color.blue < 0.45f -> "Yellow"
        color.red > 0.9f && color.green > 0.9f && color.blue > 0.9f -> "White"
        else -> "Custom"
    }
    return "$size · $name"
}

private fun sourceFactsFor(stream: StreamItem): SourceRowFacts {
    val bytes = stream.behaviorHints.videoSize?.takeIf { it > 0L }?.let { formatByteSize(it) }
    val parsed = sourceRowFacts(
        name = stream.streamLabel,
        description = stream.description,
        cached = stream.isCachedDebridTorrentStream || stream.debridCacheStatus?.state?.name == "CACHED",
    )
    return if (bytes.isNullOrBlank()) parsed else parsed.copy(sizeLabel = parsed.sizeLabel ?: bytes)
}

@Composable
private fun sourceSubtitle(facts: SourceRowFacts): String {
    val cached = stringResource(Res.string.player_tools_cached)
    return listOfNotNull(
        facts.quality,
        facts.sizeLabel,
        facts.seeders?.let { "$it seeders" },
        cached.takeIf { facts.cached },
    ).joinToString(" · ")
}

private fun StreamItem.stableKey(): String = listOf(
    addonId,
    infoHash ?: url ?: streamLabel,
    fileIdx ?: -1,
).joinToString(":")

private fun JsonObject?.intOrSnapshot(key: String): Int =
    this?.get(key)?.let { runCatching { it.jsonPrimitiveInt() }.getOrNull() } ?: 0

private fun JsonObject.stringOrEmpty(key: String): String =
    get(key)?.let { runCatching { it.toString().trim('"') }.getOrNull() }.orEmpty()

private fun JsonObject.intOrZero(key: String): Int =
    get(key)?.let { runCatching { it.jsonPrimitiveInt() }.getOrNull() } ?: 0

private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveInt(): Int =
    toString().trim('"').toIntOrNull() ?: toString().trim('"').toDoubleOrNull()?.toInt() ?: 0

private val StreamItem.description: String?
    get() = streamSubtitle
