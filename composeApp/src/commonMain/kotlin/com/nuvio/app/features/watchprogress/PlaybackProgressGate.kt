package com.nuvio.app.features.watchprogress

/**
 * Submit mode and a private session set [com.nuvio.app.features.player.PlayerLaunch.persistsProgress]
 * false. The player also folds the saved Private Viewing switch into that same flag.
 * The value is copied onto [WatchProgressPlaybackSession.persistsProgress].
 *
 * Every progress writer calls [allowsProgressWrite] and returns before it changes
 * watch progress, resume, watched state, Continue Watching, scrobbles, the offline
 * outbox, next-up, watch history, or a completion-driven download.
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
