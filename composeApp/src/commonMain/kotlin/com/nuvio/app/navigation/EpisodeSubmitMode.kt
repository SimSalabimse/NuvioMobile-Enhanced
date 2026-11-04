package com.nuvio.app.navigation

/**
 * One episode opened only to submit or fix a segment.
 *
 * [openEpisodeInSubmitMode] is the public entry. A season overview calls it
 * with the same episode identity the details screen uses to play.
 */
data class EpisodeSubmitRequest(
    val type: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
)

private var episodeSubmitLauncher: ((EpisodeSubmitRequest) -> Unit)? = null

/** Installed by the app shell while it is composed. */
internal fun installEpisodeSubmitLauncher(launcher: ((EpisodeSubmitRequest) -> Unit)?) {
    episodeSubmitLauncher = launcher
}

/**
 * Opens [request] in the in-app player in submit mode.
 *
 * Playback starts at the beginning so a forgotten intro is reachable. The stored
 * resume position, watched flag, Continue Watching row, scrobbles, next-up, and
 * watch history stay as they were. The submit panel opens with the player.
 * Leaving the player returns to the screen that opened it.
 *
 * The shell installs the launcher while it is on screen. A call before that does nothing.
 */
fun openEpisodeInSubmitMode(request: EpisodeSubmitRequest) {
    episodeSubmitLauncher?.invoke(request)
}
