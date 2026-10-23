package com.nuvio.app.features.debrid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What [DebridSourceFallback.playable] ended with: a stream ready to use, or why there is none. */
internal data class DebridPlayOutcome(
    val stream: StreamItem?,
    val failure: DirectDebridPlayableResult? = null,
)

/**
 * The one way play and download entry points turn a source into a usable link. A debrid source
 * that is not cached (or stale) falls back to the next few [candidates]; when none works, the
 * user gets "Add to TorBox" (or the error). DebridSourceGuardTest fails when a play/download
 * file calls the resolver directly.
 */
internal object DebridSourceFallback {
    const val MAX_TRIES = 3

    /**
     * [stream] ready to play or download. [candidates] is only loaded when a fallback is needed,
     * in the caller's preferred order. With [notify] the switch note, the add offer or the error
     * is shown here; without it the caller decides (batch, auto-play) via [notifyFailure].
     */
    suspend fun playable(
        stream: StreamItem,
        season: Int?,
        episode: Int?,
        notify: Boolean = true,
        candidates: suspend () -> List<StreamItem>,
    ): DebridPlayOutcome {
        if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) return DebridPlayOutcome(stream)
        val first = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode)
        if (first is DirectDebridPlayableResult.Success) return DebridPlayOutcome(first.stream)
        if (first is DirectDebridPlayableResult.NotCached || first is DirectDebridPlayableResult.Stale) {
            nextWorking(candidates(), stream, season, episode)?.let { fallback ->
                if (notify) NuvioToastController.show("Not cached, switched to ${fallback.fallbackLabel()}", durationMillis = 4000L)
                return DebridPlayOutcome(fallback)
            }
        }
        if (notify) notifyFailure(stream, first)
        return DebridPlayOutcome(null, first)
    }

    /** Nothing worked for [stream]: offer to add it to the debrid cache, else show the error. */
    fun notifyFailure(stream: StreamItem, failure: DirectDebridPlayableResult) {
        if (failure is DirectDebridPlayableResult.NotCached && DebridAddOffer.offer(stream)) return
        failure.toastMessage()?.let { NuvioToastController.show(it) }
    }

    /** First of the next few [candidates] (skipping [failed] and known-uncached ones) that resolves. */
    suspend fun nextWorking(
        candidates: List<StreamItem>,
        failed: StreamItem,
        season: Int?,
        episode: Int?,
    ): StreamItem? = candidates
        .asSequence()
        .filter { it != failed && it.debridCacheStatus?.state != StreamDebridCacheState.NOT_CACHED }
        .filter { it.playableDirectUrl != null || DirectDebridPlaybackResolver.shouldResolveToPlayableStream(it) }
        .take(MAX_TRIES)
        .toList()
        .firstNotNullOfOrNull { candidate ->
            (DirectDebridPlaybackResolver.resolveToPlayableStream(candidate, season, episode) as? DirectDebridPlayableResult.Success)
                ?.stream?.takeIf { it.playableDirectUrl != null }
        }
}

/** App-wide "Add to TorBox" offer, so it shows whichever screen (or closed sheet) asked. */
internal object DebridAddOffer {
    private val _pending = MutableStateFlow<StreamItem?>(null)
    val pending: StateFlow<StreamItem?> = _pending.asStateFlow()

    /** True when [stream]'s provider can add it and the offer is now showing. */
    fun offer(stream: StreamItem): Boolean {
        if (DirectDebridPlaybackResolver.addToCacheProviderName(stream) == null) return false
        _pending.value = stream
        return true
    }

    fun dismiss() {
        _pending.value = null
    }
}

/** Shown once at the app root. */
@Composable
internal fun DebridAddOfferHost() {
    val offered by DebridAddOffer.pending.collectAsState()
    val stream = offered ?: return
    val provider = DirectDebridPlaybackResolver.addToCacheProviderName(stream) ?: "debrid"
    val scope = rememberCoroutineScope()
    var adding by remember(stream) { mutableStateOf(false) }
    NuvioStatusModal(
        title = "Not cached on $provider",
        message = "No other source worked. Add it to $provider so it downloads there and is ready to play later?",
        isVisible = true,
        isBusy = adding,
        confirmText = "Add to $provider",
        dismissText = "Not now",
        onConfirm = {
            adding = true
            scope.launch {
                val added = DirectDebridPlaybackResolver.addToCache(stream)
                DebridAddOffer.dismiss()
                NuvioToastController.show(
                    if (added) "Added to $provider. It will be ready to play later." else "$provider didn't accept it. Try again later.",
                    durationMillis = 4000L,
                )
            }
        },
        onDismiss = { DebridAddOffer.dismiss() },
    )
}

/** Short "which source" text for the switch note: addon and its stream label, first line only. */
internal fun StreamItem.fallbackLabel(): String =
    listOf(addonName, streamLabel.lineSequence().firstOrNull().orEmpty())
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
        .take(60)
