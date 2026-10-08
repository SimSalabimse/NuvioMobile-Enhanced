package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.features.p2p.formatP2pSpeed
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_stall_arriving_slower
import nuvio.composeapp.generated.resources.player_stall_buffer_ahead
import nuvio.composeapp.generated.resources.player_stall_buffering
import nuvio.composeapp.generated.resources.player_stall_connection_offline
import nuvio.composeapp.generated.resources.player_stall_filling_buffer
import nuvio.composeapp.generated.resources.player_stall_host_not_responding
import nuvio.composeapp.generated.resources.player_stall_host_rejected
import nuvio.composeapp.generated.resources.player_stall_host_silent
import nuvio.composeapp.generated.resources.player_stall_not_enough
import nuvio.composeapp.generated.resources.player_stall_playback_needs
import nuvio.composeapp.generated.resources.player_stall_player_catching_up
import nuvio.composeapp.generated.resources.player_stall_points_at_host
import nuvio.composeapp.generated.resources.player_stall_reading_local_file
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal const val PlaybackStallEvidenceMs = 1_500L
internal const val PlaybackStallSilentBytesPerSec = 8_192L

enum class PlaybackStallSource {
    LocalFile,
    Remote,
}

enum class PlaybackStallFailure {
    None,
    Http4xx,
    Http5xx,
    Timeout,
    ConnectionReset,
}

enum class PlaybackStallCause {
    ReadingLocalFile,
    ConnectionOffline,
    FillingBuffer,
    HostRejected,
    HostNotResponding,
    HostSilent,
    ArrivingSlowerThanPlayback,
    PlayerCatchingUp,
    NotEnoughEvidence,
}

data class PlaybackStallSample(
    val source: PlaybackStallSource,
    val network: NetworkCondition,
    val stalledForMs: Long,
    val failure: PlaybackStallFailure = PlaybackStallFailure.None,
    val incomingBytesPerSec: Long? = null,
    val mediaBitrateBps: Long? = null,
)

data class PlaybackStallCopy(
    val headline: String,
    val detail: String?,
) {
    val overlayText: String
        get() = listOfNotNull(headline, detail?.takeIf { it.isNotBlank() }).joinToString("\n")
}

internal fun classifyPlaybackStall(sample: PlaybackStallSample): PlaybackStallCause {
    if (sample.source == PlaybackStallSource.LocalFile) {
        return PlaybackStallCause.ReadingLocalFile
    }
    if (sample.network == NetworkCondition.NoInternet) {
        return PlaybackStallCause.ConnectionOffline
    }
    if (sample.stalledForMs < PlaybackStallEvidenceMs) {
        return PlaybackStallCause.FillingBuffer
    }
    when (sample.failure) {
        PlaybackStallFailure.Http4xx -> return PlaybackStallCause.HostRejected
        PlaybackStallFailure.Timeout,
        PlaybackStallFailure.Http5xx,
        PlaybackStallFailure.ConnectionReset,
        -> return PlaybackStallCause.HostNotResponding
        PlaybackStallFailure.None -> Unit
    }
    val incoming = sample.incomingBytesPerSec
    if (
        incoming != null &&
        incoming < PlaybackStallSilentBytesPerSec &&
        sample.stalledForMs >= PlaybackStallEvidenceMs
    ) {
        return when (sample.network) {
            NetworkCondition.Online,
            NetworkCondition.ServersUnreachable,
            -> PlaybackStallCause.HostSilent
            NetworkCondition.Unknown,
            NetworkCondition.Checking,
            NetworkCondition.NoInternet,
            -> PlaybackStallCause.NotEnoughEvidence
        }
    }
    val bitrate = sample.mediaBitrateBps?.takeIf { it > 0L }
    if (bitrate != null && incoming != null && incomingArrivesSlowerThanPlayback(incoming, bitrate)) {
        return PlaybackStallCause.ArrivingSlowerThanPlayback
    }
    val catchingUp = when {
        bitrate != null && incoming != null && !incomingArrivesSlowerThanPlayback(incoming, bitrate) -> true
        bitrate == null && incoming != null && incoming >= PlaybackStallSilentBytesPerSec -> true
        else -> false
    }
    if (catchingUp) return PlaybackStallCause.PlayerCatchingUp
    return PlaybackStallCause.NotEnoughEvidence
}

internal fun playbackStallFailureForHttpStatus(statusCode: Int): PlaybackStallFailure = when (statusCode) {
    in 400..499 -> PlaybackStallFailure.Http4xx
    in 500..599 -> PlaybackStallFailure.Http5xx
    else -> PlaybackStallFailure.None
}

internal fun stallPointsAtStreamHost(network: NetworkCondition): Boolean =
    network == NetworkCondition.Online || network == NetworkCondition.ServersUnreachable

internal fun resolveStallMediaBitrateBps(reportedBps: Long?, hlsBandwidthBps: Long?): Long? =
    reportedBps?.takeIf { it > 0L } ?: hlsBandwidthBps?.takeIf { it > 0L }

internal fun playbackStallSource(url: String?): PlaybackStallSource {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return PlaybackStallSource.Remote
    val lower = value.lowercase()
    if (lower.startsWith("file:") || lower.startsWith("content:")) {
        return PlaybackStallSource.LocalFile
    }
    if (value.startsWith("/") || value.startsWith("\\") || !hasUrlScheme(value)) {
        return PlaybackStallSource.LocalFile
    }
    return PlaybackStallSource.Remote
}

internal fun streamHostFromUrl(url: String?): String? {
    if (playbackStallSource(url) == PlaybackStallSource.LocalFile) return null
    val value = url?.trim().orEmpty()
    val schemeSeparator = value.indexOf("://")
    if (schemeSeparator < 0) return null
    val afterScheme = value.substring(schemeSeparator + 3)
    val withoutFragment = afterScheme.substringBefore('#')
    val authority = withoutFragment.substringBefore('?').substringBefore('/')
    if (authority.isEmpty()) return null
    val hostPort = authority.substringAfterLast('@').trim()
    if (hostPort.isEmpty()) return null
    val host = if (hostPort.startsWith("[")) {
        hostPort.substringAfter('[').substringBefore(']')
    } else if (hostPort.count { it == ':' } == 1) {
        hostPort.substringBefore(':')
    } else {
        hostPort
    }.trim()
    if (host.isEmpty() || host.contains('/') || host.contains('?') || host.contains('@')) return null
    return host
}

internal fun displayStreamHost(streamHost: String?, sourceUrl: String?): String? {
    val explicit = streamHost?.trim().orEmpty()
    if (
        explicit.isNotEmpty() &&
        "://" !in explicit &&
        '/' !in explicit &&
        '?' !in explicit &&
        '@' !in explicit &&
        ' ' !in explicit
    ) {
        return explicit
    }
    return streamHostFromUrl(explicit.ifEmpty { null }) ?: streamHostFromUrl(sourceUrl)
}

@Composable
internal fun rememberPlaybackStallElapsedMs(isLoading: Boolean): Long {
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isLoading) {
        if (!isLoading) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val started = TimeSource.Monotonic.markNow()
        elapsedMs = 0L
        while (isActive) {
            elapsedMs = started.elapsedNow().inWholeMilliseconds
            delay(250L)
        }
    }
    return elapsedMs
}

@Composable
internal fun playbackStallCopy(
    sourceUrl: String?,
    network: NetworkCondition,
    stalledForMs: Long,
    snapshot: PlayerPlaybackSnapshot,
    bufferAheadMs: Long,
    hlsBandwidthBps: Long?,
): PlaybackStallCopy {
    val mediaBitrateBps = resolveStallMediaBitrateBps(snapshot.mediaBitrateBps, hlsBandwidthBps)
    val sample = PlaybackStallSample(
        source = playbackStallSource(sourceUrl),
        network = network,
        stalledForMs = stalledForMs,
        failure = snapshot.stallFailure,
        incomingBytesPerSec = snapshot.incomingBytesPerSec,
        mediaBitrateBps = mediaBitrateBps,
    )
    val cause = classifyPlaybackStall(sample)
    return PlaybackStallCopy(
        headline = stringResource(cause.headlineResource()),
        detail = stallDetail(
            cause = cause,
            network = network,
            bufferAheadMs = bufferAheadMs,
            incomingBytesPerSec = sample.incomingBytesPerSec,
            mediaBitrateBps = mediaBitrateBps,
            streamHost = displayStreamHost(snapshot.streamHost, sourceUrl),
        ),
    )
}

@Composable
internal fun PlaybackStallRebufferStatus(
    headline: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.78f),
            textAlign = TextAlign.Center,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun stallDetail(
    cause: PlaybackStallCause,
    network: NetworkCondition,
    bufferAheadMs: Long,
    incomingBytesPerSec: Long?,
    mediaBitrateBps: Long?,
    streamHost: String?,
): String? {
    val ahead = bufferAheadMs.takeIf { it >= 1_000L }?.let { aheadMs ->
        stringResource(Res.string.player_stall_buffer_ahead, (aheadMs / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    val speed = incomingBytesPerSec?.let { formatP2pSpeed(it) }
    val bitrate = mediaBitrateBps?.let { formatStreamBitrate(it) }
    val facts = listOfNotNull(ahead, speed, bitrate, streamHost?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
        .ifBlank { null }
    return when (cause) {
        PlaybackStallCause.ArrivingSlowerThanPlayback -> {
            val needs = if (bitrate != null && speed != null) {
                stringResource(Res.string.player_stall_playback_needs, bitrate, speed)
            } else {
                null
            }
            val points = if (stallPointsAtStreamHost(network)) {
                stringResource(Res.string.player_stall_points_at_host)
            } else {
                null
            }
            val extra = listOfNotNull(ahead, streamHost?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
                .ifBlank { null }
            listOfNotNull(needs, points, extra).joinToString(" ").ifBlank { null }
        }
        PlaybackStallCause.NotEnoughEvidence -> {
            val sentence = stringResource(Res.string.player_stall_not_enough)
            if (facts == null) sentence else "$sentence $facts"
        }
        else -> facts
    }
}

private fun PlaybackStallCause.headlineResource(): StringResource = when (this) {
    PlaybackStallCause.ReadingLocalFile -> Res.string.player_stall_reading_local_file
    PlaybackStallCause.ConnectionOffline -> Res.string.player_stall_connection_offline
    PlaybackStallCause.FillingBuffer -> Res.string.player_stall_filling_buffer
    PlaybackStallCause.HostRejected -> Res.string.player_stall_host_rejected
    PlaybackStallCause.HostNotResponding -> Res.string.player_stall_host_not_responding
    PlaybackStallCause.HostSilent -> Res.string.player_stall_host_silent
    PlaybackStallCause.ArrivingSlowerThanPlayback -> Res.string.player_stall_arriving_slower
    PlaybackStallCause.PlayerCatchingUp -> Res.string.player_stall_player_catching_up
    PlaybackStallCause.NotEnoughEvidence -> Res.string.player_stall_buffering
}

internal fun formatStreamBitrate(bitsPerSec: Long): String? {
    if (bitsPerSec <= 0L) return null
    val mbps = bitsPerSec / 1_000_000.0
    return if (mbps >= 1.0) {
        "${formatStallOneDecimal(mbps)} Mbps"
    } else {
        "${formatStallOneDecimal(bitsPerSec / 1_000.0)} Kbps"
    }
}

private fun incomingArrivesSlowerThanPlayback(incomingBytesPerSec: Long, mediaBitrateBps: Long): Boolean {
    // incomingBytes * 8 < mediaBitrate * 0.8, compared without a fractional multiply.
    if (incomingBytesPerSec > Long.MAX_VALUE / 10L) return false
    return incomingBytesPerSec * 10L < mediaBitrateBps
}

private fun hasUrlScheme(value: String): Boolean {
    val scheme = value.substringBefore(':', missingDelimiterValue = "")
    if (scheme.isEmpty() || scheme == value || !scheme[0].isLetter()) return false
    return scheme.all { character ->
        character.isLetterOrDigit() || character == '+' || character == '.' || character == '-'
    }
}

private fun formatStallOneDecimal(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    val text = rounded.toString()
    return if (text.endsWith(".0")) text.dropLast(2) else text
}
