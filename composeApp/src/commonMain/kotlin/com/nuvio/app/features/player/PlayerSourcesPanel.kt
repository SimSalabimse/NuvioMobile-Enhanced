package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_episode_code_full
import nuvio.composeapp.generated.resources.compose_player_panel_sources
import nuvio.composeapp.generated.resources.compose_player_playing
import org.jetbrains.compose.resources.stringResource

@Composable
fun PlayerSourcesPanel(
    visible: Boolean,
    streamsUiState: StreamsUiState,
    contentTitle: String,
    currentSeason: Int?,
    currentEpisode: Int?,
    currentEpisodeTitle: String?,
    currentStreamUrl: String?,
    currentStreamName: String?,
    onFilterSelected: (String?) -> Unit,
    onStreamSelected: (StreamItem) -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentLabel = if (currentSeason != null && currentEpisode != null) {
        buildString {
            append(stringResource(Res.string.compose_player_episode_code_full, currentSeason, currentEpisode))
            currentEpisodeTitle?.takeIf { it.isNotBlank() }?.let {
                append(" • ")
                append(it)
            }
        }
    } else {
        contentTitle
    }

    PlayerToolPanel(
        visible = visible,
        title = stringResource(Res.string.compose_player_panel_sources),
        subtitle = contentLabel,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        PlayerProviderFilterRow(
            streamsUiState = streamsUiState,
            onFilterSelected = onFilterSelected,
            onRefresh = onReload,
        )

        Spacer(Modifier.height(12.dp))

        PlayerStreamList(
            streamsUiState = streamsUiState,
            onStreamSelected = onStreamSelected,
            modifier = Modifier.weight(1f),
            currentStreamUrl = currentStreamUrl,
            currentStreamName = currentStreamName,
            currentLabel = stringResource(Res.string.compose_player_playing),
        )
    }
}

internal fun List<StreamItem>.stablePlayerKeys(): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return map { stream ->
        val base = listOf(
            stream.addonId,
            stream.infoHash ?: stream.clientResolve?.infoHash ?: stream.url ?: stream.externalUrl ?: stream.streamLabel,
            stream.fileIdx ?: stream.clientResolve?.fileIdx ?: -1,
        ).joinToString("::")
        val count = occurrences[base] ?: 0
        occurrences[base] = count + 1
        "$base::$count"
    }
}

internal fun StreamItem.isCurrentPlayerStream(
    currentUrl: String?,
    currentName: String?,
): Boolean {
    if (!currentUrl.isNullOrBlank() && playableDirectUrl == currentUrl) return true
    return !currentName.isNullOrBlank() && streamLabel.equals(currentName, ignoreCase = true) &&
        playableDirectUrl == currentUrl
}
