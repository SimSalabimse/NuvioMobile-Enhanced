package com.nuvio.app.features.watchprogress

/**
 * Submit mode is [com.nuvio.app.features.player.PlayerLaunch.submitMode].
 * That launch's [com.nuvio.app.features.player.PlayerLaunch.persistsProgress] is then false,
 * and the same value is copied onto [WatchProgressPlaybackSession.persistsProgress].
 *
 * Every progress writer calls [allowsProgressWrite] and returns before it changes
 * watch progress, resume, watched state, Continue Watching, scrobbles, next-up,
 * watch history, or a completion-driven download. There is no other submit-mode condition.
 */
internal fun allowsProgressWrite(persistsProgress: Boolean): Boolean = persistsProgress

/**
 * Names of writers that passed [allowsProgressWrite] and started their real work.
 * Submit mode leaves this empty. Tests read it; production does not branch on it.
 */
internal object ProgressWriteTrace {
    private val names = mutableListOf<String>()

    fun note(writer: String) {
        names.add(writer)
    }

    fun snapshot(): List<String> = names.toList()

    fun clear() {
        names.clear()
    }
}
