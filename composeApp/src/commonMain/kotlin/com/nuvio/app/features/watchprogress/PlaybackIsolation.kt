package com.nuvio.app.features.watchprogress

/**
 * What one playback is allowed to change.
 *
 * Upstream Private Viewing is the saved settings switch. It already stops local
 * progress, Continue Watching, watched state, the offline outbox, and tracker
 * scrobbles. It does not stop server playback events, autoplay, or a rewatch
 * side effect, and it is not scoped to one episode.
 *
 * A private session and submit mode use the same block list for this playback
 * only. Neither one writes the settings switch.
 */
internal enum class PlaybackSideEffect {
    LocalProgress,
    ContinueWatching,
    WatchedState,
    OfflineOutbox,
    TraktScrobble,
    SimklScrobble,
    ExtensionScrobble,
    ServerPlayback,
    Autoplay,
    Rewatch,
}

/** Effects the saved Private Viewing switch already stops. */
internal val upstreamPrivateViewingEffects: Set<PlaybackSideEffect> = setOf(
    PlaybackSideEffect.LocalProgress,
    PlaybackSideEffect.ContinueWatching,
    PlaybackSideEffect.WatchedState,
    PlaybackSideEffect.OfflineOutbox,
    PlaybackSideEffect.TraktScrobble,
    PlaybackSideEffect.SimklScrobble,
    PlaybackSideEffect.ExtensionScrobble,
)

internal data class PlaybackIsolation(
    val submitMode: Boolean = false,
    val privateSession: Boolean = false,
    val globalPrivateViewing: Boolean = false,
) {
    val active: Boolean get() = submitMode || privateSession || globalPrivateViewing

    /** False for every side effect while this playback is isolated. */
    fun allows(effect: PlaybackSideEffect): Boolean = !active

    val persistsProgress: Boolean get() = !active

    /**
     * Autoplay is stopped for a private session and for submit mode.
     * The saved switch does not cancel it; that playback still does not write.
     */
    val allowsAutoplay: Boolean get() = !submitMode && !privateSession

    /** A session never turns the saved switch on or off. */
    val writesGlobalPrivateViewing: Boolean get() = false
}

internal fun playbackIsolation(
    submitMode: Boolean = false,
    privateSession: Boolean = false,
    globalPrivateViewing: Boolean = false,
): PlaybackIsolation = PlaybackIsolation(
    submitMode = submitMode,
    privateSession = privateSession,
    globalPrivateViewing = globalPrivateViewing,
)

internal data class ContinueWatchingRow(
    val episode: Int,
    val positionMs: Long,
)

internal data class PlaybackRecord(
    val continueWatching: ContinueWatchingRow,
    val watchedEpisodes: Set<Int> = emptySet(),
    val outbox: List<String> = emptyList(),
    val scrobbles: List<String> = emptyList(),
    val extensionEvents: List<String> = emptyList(),
    val serverEvents: List<String> = emptyList(),
    val autoplay: List<Int> = emptyList(),
    val globalPrivateViewingEnabled: Boolean = false,
)

internal enum class PlaybackMoment {
    Start,
    Pause,
    Seek,
    Stop,
    Completion,
    Exit,
}

/**
 * Applies one episode's playback moments to a local record.
 * Isolated playback returns the record unchanged, including after completion.
 */
internal fun PlaybackRecord.play(
    episode: Int,
    isolation: PlaybackIsolation,
    moments: List<PlaybackMoment>,
    positionMs: Long = 30_000L,
    completedPositionMs: Long = 1_200_000L,
): PlaybackRecord {
    if (!isolation.persistsProgress && !isolation.allowsAutoplay) {
        return copy(globalPrivateViewingEnabled = globalPrivateViewingEnabled || isolation.writesGlobalPrivateViewing)
    }
    var row = continueWatching
    val watched = watchedEpisodes.toMutableSet()
    val outbox = outbox.toMutableList()
    val scrobbles = scrobbles.toMutableList()
    val extension = extensionEvents.toMutableList()
    val server = serverEvents.toMutableList()
    val autoplay = autoplay.toMutableList()
    moments.forEach { moment ->
        if (isolation.allows(PlaybackSideEffect.ServerPlayback)) server += "$episode:${moment.name}"
        if (moment == PlaybackMoment.Completion && isolation.allowsAutoplay) autoplay += episode + 1
        if (!isolation.persistsProgress) return@forEach
        when (moment) {
            PlaybackMoment.Completion -> {
                row = ContinueWatchingRow(episode, completedPositionMs)
                if (isolation.allows(PlaybackSideEffect.WatchedState)) watched += episode
                if (isolation.allows(PlaybackSideEffect.OfflineOutbox)) outbox += "$episode:completion"
                if (isolation.allows(PlaybackSideEffect.TraktScrobble)) scrobbles += "trakt:$episode:completion"
                if (isolation.allows(PlaybackSideEffect.SimklScrobble)) scrobbles += "simkl:$episode:completion"
                if (isolation.allows(PlaybackSideEffect.ExtensionScrobble)) extension += "$episode:completion"
                if (isolation.allows(PlaybackSideEffect.Rewatch)) extension += "$episode:rewatch"
            }
            PlaybackMoment.Start,
            PlaybackMoment.Pause,
            PlaybackMoment.Seek,
            PlaybackMoment.Stop,
            PlaybackMoment.Exit,
            -> {
                if (isolation.allows(PlaybackSideEffect.LocalProgress) &&
                    isolation.allows(PlaybackSideEffect.ContinueWatching)
                ) {
                    row = ContinueWatchingRow(episode, positionMs)
                }
                if (isolation.allows(PlaybackSideEffect.OfflineOutbox)) outbox += "$episode:${moment.name}"
                if (isolation.allows(PlaybackSideEffect.TraktScrobble)) scrobbles += "trakt:$episode:${moment.name}"
                if (isolation.allows(PlaybackSideEffect.SimklScrobble)) scrobbles += "simkl:$episode:${moment.name}"
                if (isolation.allows(PlaybackSideEffect.ExtensionScrobble)) extension += "$episode:${moment.name}"
            }
        }
    }
    return copy(
        continueWatching = row,
        watchedEpisodes = watched,
        outbox = outbox,
        scrobbles = scrobbles,
        extensionEvents = extension,
        serverEvents = server,
        autoplay = autoplay,
        globalPrivateViewingEnabled = globalPrivateViewingEnabled || isolation.writesGlobalPrivateViewing,
    )
}
