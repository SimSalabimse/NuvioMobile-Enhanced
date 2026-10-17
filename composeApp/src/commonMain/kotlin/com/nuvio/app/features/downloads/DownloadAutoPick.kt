package com.nuvio.app.features.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Outcome of [runDownloadAutoPick]. */
internal sealed interface DownloadAutoPickState {
    data object Off : DownloadAutoPickState
    data class Picking(val pendingSources: Int) : DownloadAutoPickState
    data class Picked(val stream: StreamItem) : DownloadAutoPickState
    data object GaveUp : DownloadAutoPickState
}

/**
 * The Download Season auto-pick for one episode or movie: same sources (with the TorBox/debrid
 * cache check), same selector as auto-play ([evaluateSeasonAutoPick]), same timing
 * ([seasonAutoPickMayDecide]) and the same give-up rule ([seasonAutoPickShouldGiveUp]).
 */
internal suspend fun runDownloadAutoPick(
    type: String,
    videoId: String,
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    onState: (DownloadAutoPickState) -> Unit,
    onStreams: (streams: List<StreamItem>, pickFrom: (List<StreamItem>) -> StreamItem?) -> Unit = { _, _ -> },
) = coroutineScope {
    val settings = currentAutoPickSettings(parentMetaId)
    if (!settings.picksAutomatically()) {
        onState(DownloadAutoPickState.Off)
        return@coroutineScope
    }
    val installed = installedAddonNames()
    val quality = SmartDownloadSettings.snapshot().quality
    var streams: List<StreamItem> = emptyList()
    var allAnswered = false
    var timeoutElapsed = false
    var hardTimeoutElapsed = false
    var done = false

    fun decide() {
        if (done) return
        val result = evaluateSeasonAutoPick(streams.orderedForQuality(quality), settings, installed)
        val mayDecide = seasonAutoPickMayDecide(settings.timeoutSeconds, timeoutElapsed, allAnswered) || hardTimeoutElapsed
        if (result.stream != null && mayDecide) {
            done = true
            onState(DownloadAutoPickState.Picked(result.stream))
        } else if (seasonAutoPickShouldGiveUp(result, allAnswered, hardTimeoutElapsed)) {
            done = true
            onState(DownloadAutoPickState.GaveUp)
        }
    }

    onState(DownloadAutoPickState.Picking(pendingSources = 1))
    val timer = launch {
        val seconds = settings.timeoutSeconds
        if (seconds in 1 until Int.MAX_VALUE) {
            delay(seconds * 1_000L)
            timeoutElapsed = true
            decide()
        }
    }
    val hardTimer = launch {
        delay(SEASON_AUTO_PICK_GIVE_UP_MS)
        hardTimeoutElapsed = true
        decide()
    }
    SeasonStreamCatalog.stream(type, videoId, season, episode, annotateDebridCache = true).collect { snapshot ->
        streams = snapshot.streams
        allAnswered = snapshot.complete
        onStreams(snapshot.streams) { list -> evaluateSeasonAutoPick(list, settings, installed).stream }
        if (!done) onState(DownloadAutoPickState.Picking(snapshot.pendingSources))
        decide()
    }
    if (done) {
        timer.cancel()
        hardTimer.cancel()
    } else {
        timer.join()
        hardTimer.join()
    }
}

/**
 * State of the single-episode / movie download auto-pick, shared by every download entry point
 * that opens the stream screen in download mode (details sheet, episode rows, the Continue
 * Watching / Next Up hold menu). While it is picking or has picked, the source list stays hidden;
 * "Change source" or "Choose myself" reveals it.
 */
@Stable
internal class DownloadAutoPickController {
    var state by mutableStateOf<DownloadAutoPickState>(DownloadAutoPickState.Picking(1))
        internal set
    var revealed by mutableStateOf(false)
        internal set

    /** True while the card replaces the source list. */
    val hidesSourceList: Boolean
        get() = !revealed && (state is DownloadAutoPickState.Picking || state is DownloadAutoPickState.Picked)

    fun revealSources() {
        revealed = true
    }

    /** Best auto-pick candidate per quality present (4K / 1080p / 720p / 480p). */
    var qualityOptions by mutableStateOf<List<DownloadQualityOption>>(emptyList())
        internal set

    /** Swap the pick to the best candidate of another quality, without opening the list. */
    fun selectQuality(option: DownloadQualityOption) {
        if (state is DownloadAutoPickState.Picked) state = DownloadAutoPickState.Picked(option.stream)
    }
}

@Composable
internal fun rememberDownloadAutoPick(
    type: String,
    videoId: String,
    parentMetaId: String,
    season: Int?,
    episode: Int?,
    enabled: Boolean,
): DownloadAutoPickController {
    val controller = remember(videoId, enabled) {
        DownloadAutoPickController().also { if (!enabled) it.state = DownloadAutoPickState.Off }
    }
    if (enabled) {
        LaunchedEffect(controller) {
            runDownloadAutoPick(
                type = type,
                videoId = videoId,
                parentMetaId = parentMetaId,
                season = season,
                episode = episode,
                onState = { controller.state = it },
                onStreams = { streams, pickFrom -> controller.qualityOptions = bestPerQualityOptions(streams, pickFrom) },
            )
        }
    }
    return controller
}

/**
 * The picked-source card: "Picking…" while choosing, then the picked source with quality and
 * size chips. "Change source" / "Choose myself" shows the full list; "Download" queues the pick.
 */
@Composable
internal fun DownloadAutoPickCard(
    controller: DownloadAutoPickController,
    heading: String?,
    onDownload: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!controller.hidesSourceList) return
    val current = controller.state
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            heading?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when (current) {
                is DownloadAutoPickState.Picking -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Picking…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = controller::revealSources) { Text("Choose myself") }
                }
                is DownloadAutoPickState.Picked -> {
                    val stream = current.stream
                    Text("Auto-picked source", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = stream.addonName.ifBlank { streamQualityLabel(stream) },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val options = controller.qualityOptions
                    if (options.isNotEmpty()) {
                        DownloadQualityOptionsRow(
                            options = options,
                            selectedBucket = downloadQualityBucket(stream),
                            onSelect = controller::selectQuality,
                        )
                    } else {
                        DownloadQualitySizeChips(stream = stream)
                    }
                    stream.streamLabel.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = controller::revealSources) { Text("Change source") }
                        Row(modifier = Modifier.weight(1f)) {}
                        Button(onClick = {
                            controller.revealSources()
                            onDownload(stream)
                        }) { Text("Download") }
                    }
                }
                else -> Unit
            }
        }
    }
}
