package com.nuvio.app.features.downloads

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.download_quality_best
import nuvio.composeapp.generated.resources.download_quality_2160
import nuvio.composeapp.generated.resources.download_quality_1080
import nuvio.composeapp.generated.resources.download_quality_720
import nuvio.composeapp.generated.resources.download_quality_480
import nuvio.composeapp.generated.resources.download_quality_sheet_title
import nuvio.composeapp.generated.resources.download_quality_free_space
import nuvio.composeapp.generated.resources.download_quality_not_enough_space
import nuvio.composeapp.generated.resources.download_quality_cancel
import nuvio.composeapp.generated.resources.download_quality_confirm
import nuvio.composeapp.generated.resources.download_quality_confirm_size

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.streams.StreamItem
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun downloadQualityLabel(quality: DownloadQualityPreference): String = when (quality) {
    DownloadQualityPreference.Best -> stringResource(Res.string.download_quality_best)
    DownloadQualityPreference.P2160 -> stringResource(Res.string.download_quality_2160)
    DownloadQualityPreference.P1080 -> stringResource(Res.string.download_quality_1080)
    DownloadQualityPreference.P720 -> stringResource(Res.string.download_quality_720)
    DownloadQualityPreference.P480 -> stringResource(Res.string.download_quality_480)
}

private val sizeInTextRegex = Regex("""(?i)(\d+(?:[.,]\d+)?)\s*(GB|GiB|MB|MiB)\b""")

/** Size the addon tells us: behaviorHints.videoSize, else a "12.3 GB" in the stream text. */
internal fun statedStreamBytes(stream: StreamItem): Long? {
    stream.behaviorHints.videoSize?.takeIf { it > 0L }?.let { return it }
    for (text in listOfNotNull(stream.title, stream.description, stream.name)) {
        val match = sizeInTextRegex.find(text) ?: continue
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
        val unit = match.groupValues[2].uppercase()
        val factor = if (unit.startsWith("G")) 1024.0 * 1024.0 * 1024.0 else 1024.0 * 1024.0
        return (value * factor).toLong()
    }
    return null
}

/**
 * One option per quality from the tapped stream's addon (the tapped stream stands for its own
 * quality), downloadable streams only, highest quality first.
 */
internal fun downloadQualityOptions(tapped: StreamItem, all: List<StreamItem>): List<StreamItem> {
    val sameAddon = all.filter { it.addonId == tapped.addonId && it.isSeasonDownloadCandidate() }
    val byLabel = linkedMapOf<String, StreamItem>()
    byLabel[streamQualityLabel(tapped)] = tapped
    for (stream in sameAddon) byLabel.getOrPut(streamQualityLabel(stream)) { stream }
    return byLabel.values.sortedByDescending { qualityHeight(streamQualityLabel(it)) ?: 0 }
}

/**
 * Asks which quality to download and shows the size estimate and free space before the download
 * starts. Sizes the addon does not state are read from the server (HEAD / 1-byte range).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadQualitySheet(
    tapped: StreamItem,
    allStreams: List<StreamItem>,
    onDismiss: () -> Unit,
    onConfirm: (StreamItem) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val options = remember(tapped, allStreams) { downloadQualityOptions(tapped, allStreams) }
    var selected by remember(tapped) { mutableStateOf(tapped) }
    val probed = remember(tapped) { mutableStateMapOf<String, Long?>() }
    val freeBytes = remember { usableFreeBytes(DownloadDiskInfo.volumeSpace()) }

    LaunchedEffect(options) {
        val targets = options.mapNotNull { stream ->
            if (statedStreamBytes(stream) != null) return@mapNotNull null
            val url = stream.playableDirectUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SeasonProbeTarget(
                id = url,
                url = url,
                headers = stream.behaviorHints.proxyHeaders?.request.orEmpty(),
            )
        }
        probeSeasonSizes(targets, probe = RemoteContentLengthProbe::probe) { id, bytes -> probed[id] = bytes }
    }

    fun sizeOf(stream: StreamItem): Long? =
        statedStreamBytes(stream) ?: stream.playableDirectUrl?.let { probed[it] }

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = nuvioSafeBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.download_quality_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(options, key = { (it.playableDirectUrl ?: it.title ?: it.name ?: "") + streamQualityLabel(it) }) { stream ->
                    val bytes = sizeOf(stream)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selected = stream }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected === stream, onClick = { selected = stream })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = streamQualityLabel(stream),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stream.title ?: stream.name ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = bytes?.let { formatSeasonBytes(it) } ?: "–",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            val chosenBytes = sizeOf(selected)
            if (freeBytes != null) {
                Text(
                    text = stringResource(Res.string.download_quality_free_space, formatSeasonBytes(freeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (chosenBytes != null && freeBytes != null && chosenBytes > freeBytes) {
                Text(
                    text = stringResource(Res.string.download_quality_not_enough_space),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.download_quality_cancel)) }
                Button(onClick = { onConfirm(selected) }) {
                    Text(
                        chosenBytes?.let { stringResource(Res.string.download_quality_confirm_size, formatSeasonBytes(it)) }
                            ?: stringResource(Res.string.download_quality_confirm),
                    )
                }
            }
        }
    }
}
