package com.nuvio.app.features.watching.application

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PosterResumeFractionTest {
    @Test
    fun fractionThresholds() {
        assertNull(WatchingState.posterResumeFraction(positionMs = 0, durationMs = 1_000))
        assertNull(WatchingState.posterResumeFraction(positionMs = 20, durationMs = 1_000))
        assertPercent(3, WatchingState.posterResumeFraction(positionMs = 30, durationMs = 1_000))
        assertPercent(50, WatchingState.posterResumeFraction(positionMs = 500, durationMs = 1_000))
        assertPercent(94, WatchingState.posterResumeFraction(positionMs = 940, durationMs = 1_000))
        assertNull(WatchingState.posterResumeFraction(positionMs = 950, durationMs = 1_000))
        assertNull(WatchingState.posterResumeFraction(positionMs = 1_000, durationMs = 1_000))
        assertNull(WatchingState.posterResumeFraction(positionMs = 500, durationMs = 0))
        assertNull(WatchingState.posterResumeFraction(positionMs = 1_500, durationMs = 1_000))

        assertNull(WatchingState.posterResumeFraction(0, 1_000, progressPercent = 0f))
        assertNull(WatchingState.posterResumeFraction(0, 1_000, progressPercent = 2f))
        assertPercent(3, WatchingState.posterResumeFraction(0, 0, progressPercent = 3f))
        assertPercent(50, WatchingState.posterResumeFraction(0, 0, progressPercent = 50f))
        assertPercent(94, WatchingState.posterResumeFraction(0, 0, progressPercent = 94f))
        assertNull(WatchingState.posterResumeFraction(0, 1_000, progressPercent = 95f))
        assertNull(WatchingState.posterResumeFraction(0, 1_000, progressPercent = 100f))
    }

    @Test
    fun posterProgressLookup() {
        assertNull(WatchingState.posterProgress("tt-missing", emptyMap()))

        val entries = listOf(
            movie(id = "tt-half", positionMs = 500, durationMs = 1_000),
            movie(id = "tt-zero", positionMs = 0, durationMs = 1_000),
            movie(id = "tt-done", positionMs = 1_000, durationMs = 1_000, updated = 5),
            movie(id = "tt-done", positionMs = 500, durationMs = 1_000, updated = 1),
            movie(id = "tt-show", positionMs = 500, durationMs = 1_000, type = "series"),
            movie(
                id = "tt-episode",
                positionMs = 500,
                durationMs = 1_000,
                type = "series",
                season = 1,
                episode = 2,
            ),
            movie(id = "tt-film", positionMs = 300, durationMs = 1_000, type = "film"),
            movie(id = "tt-noduration", positionMs = 400, durationMs = 0),
        )
        val byId = WatchingState.moviePosterProgressById(entries)

        assertPercent(50, WatchingState.posterProgress("tt-half", byId, type = "movie"))
        assertNull(WatchingState.posterProgress("tt-zero", byId, type = "movie"))
        assertNull(WatchingState.posterProgress("tt-done", byId, type = "movie"))
        assertNull(WatchingState.posterProgress("tt-show", byId, type = "series"))
        assertNull(WatchingState.posterProgress("tt-episode", byId, type = "series"))
        assertNull(byId["tt-show"])
        assertNull(byId["tt-episode"])
        assertNull(byId["tt-noduration"])
        assertPercent(30, WatchingState.posterProgress("tt-film", byId, type = "film"))
        assertNull(WatchingState.posterProgress("tt-half", byId, type = "series"))
        assertNull(WatchingState.posterProgress("tt-missing", byId, type = "movie"))
    }

    private fun assertPercent(expected: Int, fraction: Float?) {
        assertEquals(expected, ((fraction ?: error("expected $expected%")) * 100f).roundToInt())
    }

    private fun movie(
        id: String,
        positionMs: Long,
        durationMs: Long,
        type: String = "movie",
        season: Int? = null,
        episode: Int? = null,
        updated: Long = 1L,
    ): WatchProgressEntry = WatchProgressEntry(
        contentType = type,
        parentMetaId = id,
        parentMetaType = type,
        videoId = id,
        title = "Title",
        seasonNumber = season,
        episodeNumber = episode,
        lastPositionMs = positionMs,
        durationMs = durationMs,
        lastUpdatedEpochMs = updated,
    )
}
