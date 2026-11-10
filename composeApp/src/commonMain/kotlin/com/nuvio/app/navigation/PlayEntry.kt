package com.nuvio.app.navigation

import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.streams.StreamLaunch

/**
 * Every way a title starts playing.
 * A private session is written onto the launch here, and nowhere else.
 */
internal enum class PlayEntry {
    Details,
    EpisodeList,
    SeasonRow,
    ContinueWatching,
    SourceSheet,
    NextUp,
    DeepLink,
}

internal data class CarriedPlay(
    val entry: PlayEntry,
    val privateSession: Boolean,
    val submitMode: Boolean = false,
) {
    val isolated: Boolean get() = privateSession || submitMode
}

internal fun carriedPlay(
    entry: PlayEntry,
    privateSession: Boolean,
    submitMode: Boolean = false,
): CarriedPlay = CarriedPlay(entry, privateSession, submitMode)

/**
 * Manual Next Up keeps the private session.
 * Automatic Next Up does not start during one. Submit mode never rolls forward.
 */
internal fun nextEpisodeCarry(privateSession: Boolean, submitMode: Boolean, automatic: Boolean): CarriedPlay? {
    if (submitMode || (privateSession && automatic)) return null
    return carriedPlay(PlayEntry.NextUp, privateSession)
}

/** The only place [PlayerLaunch.privateSession] is written. */
internal fun PlayerLaunch.carrying(play: CarriedPlay): PlayerLaunch = copy(
    submitMode = play.submitMode,
    privateSession = play.privateSession,
    initialPositionMs = if (play.isolated) 0L else initialPositionMs,
    initialProgressFraction = if (play.isolated) null else initialProgressFraction,
)

/** The only place [StreamLaunch.privateSession] is written. */
internal fun StreamLaunch.carrying(play: CarriedPlay): StreamLaunch = copy(
    submitMode = play.submitMode,
    privateSession = play.privateSession,
    resumePositionMs = if (play.isolated) 0L else resumePositionMs,
    resumeProgressFraction = if (play.isolated) null else resumeProgressFraction,
    startFromBeginning = startFromBeginning || play.isolated,
    forceExternalPlayer = forceExternalPlayer && !play.isolated,
)
