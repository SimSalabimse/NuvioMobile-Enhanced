package com.nuvio.app.features.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_audio_tracks
import nuvio.composeapp.generated.resources.compose_player_no_audio_tracks_available
import org.jetbrains.compose.resources.stringResource

@Composable
fun AudioTrackModal(
    visible: Boolean,
    audioTracks: List<AudioTrack>,
    selectedIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerToolPanel(
        visible = visible,
        title = stringResource(Res.string.compose_player_audio_tracks),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        if (audioTracks.isEmpty()) {
            Text(
                text = stringResource(Res.string.compose_player_no_audio_tracks_available),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(audioTracks, key = { "${it.index}:${it.id}" }) { track ->
                    val language = track.language?.takeIf { it.isNotBlank() && it != "und" }
                    PlayerToolRow(
                        label = localizedTrackDisplayName(track.label, track.language, track.index),
                        supporting = language?.let { languageLabelForCode(it) },
                        selected = track.index == selectedIndex,
                        onClick = { onTrackSelected(track.index) },
                    )
                }
            }
        }
    }
}
