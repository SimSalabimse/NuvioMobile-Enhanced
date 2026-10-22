package com.nuvio.app.features.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.toastMessage
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What to download from a download button that should not leave the current screen. */
internal data class DownloadSheetRequest(
    val type: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
) {
    val episodeCode: String?
        get() = if (seasonNumber != null && episodeNumber != null) "S${seasonNumber}E$episodeNumber" else null

    val target: DownloadTarget
        get() = DownloadTarget(
            contentType = type, videoId = videoId, parentMetaId = parentMetaId, parentMetaType = parentMetaType,
            title = title, logo = logo, poster = poster, background = background,
            seasonNumber = seasonNumber, episodeNumber = episodeNumber,
            episodeTitle = episodeTitle, episodeThumbnail = episodeThumbnail,
        )
}

/**
 * Queues [stream] for [request] (resolving a TorBox/debrid stream to a direct link first) and
 * shows a short toast: "Downloading S2E6" or "Downloading <title>".
 */
internal fun enqueueDownloadForRequest(
    request: DownloadSheetRequest,
    stream: StreamItem,
    scope: CoroutineScope,
) {
    scope.launch {
        val result = DownloadEnqueueService.resolveAndEnqueue(request.target, stream).result ?: return@launch
        val message = if (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) {
            "Downloading ${request.episodeCode ?: request.title}"
        } else {
            result.toastMessage()
        }
        NuvioToastController.show(message)
    }
}

/**
 * The shared download card as a bottom sheet over the current screen (Home, details): auto-pick,
 * quality options and Change source all stay inside the sheet. Download dismisses it and the user
 * stays where they were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadSourceSheet(
    request: DownloadSheetRequest,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Outlives the sheet so a TorBox resolve still finishes after it closes.
    val scope = remember { CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main) }
    val controller = rememberDownloadAutoPick(
        type = request.type,
        videoId = request.videoId,
        parentMetaId = request.parentMetaId,
        season = request.seasonNumber,
        episode = request.episodeNumber,
        enabled = true,
    )
    val download: (StreamItem) -> Unit = { stream ->
        enqueueDownloadForRequest(request, stream, scope)
        onDismiss()
    }
    val showList = !controller.hidesSourceList
    var listStreams by remember(request.videoId) { mutableStateOf<List<StreamItem>>(emptyList()) }
    var listPending by remember(request.videoId) { mutableStateOf(1) }
    LaunchedEffect(request.videoId, showList) {
        if (!showList) return@LaunchedEffect
        SeasonStreamCatalog.stream(
            type = request.type,
            videoId = request.videoId,
            season = request.seasonNumber,
            episode = request.episodeNumber,
            annotateDebridCache = true,
        ).collect { snapshot ->
            listStreams = snapshot.streams
            listPending = snapshot.pendingSources
        }
    }
    val heading = listOfNotNull(
        request.title.takeIf { it.isNotBlank() },
        request.episodeCode,
        request.episodeTitle?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = nuvioSafeBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!showList) {
                DownloadAutoPickCard(
                    controller = controller,
                    heading = heading,
                    onDownload = download,
                )
                return@Column
            }
            Text(
                text = heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "Choose a source",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            val candidates = listStreams.filter { it.isSeasonDownloadCandidate() }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                itemsIndexed(candidates, key = { index, _ -> "download-source:$index" }) { _, stream ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { download(stream) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stream.addonName.ifBlank { streamQualityLabel(stream) },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DownloadQualitySizeChips(stream = stream)
                        stream.streamLabel.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (listPending > 0) {
                    item(key = "download-source-pending") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Finding sources…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else if (candidates.isEmpty()) {
                    item(key = "download-source-empty") {
                        Text(
                            text = "No downloadable sources found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
