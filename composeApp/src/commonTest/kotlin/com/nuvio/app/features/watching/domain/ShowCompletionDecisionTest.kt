package com.nuvio.app.features.watching.domain

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.continueWatchingEntries
import com.nuvio.app.features.watchprogress.shouldCascadeCompletedProgressToWatchedHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Show-finished is the same 90% rule used for a single episode.
 * A player "ended" flag at 22:58 of 43:49 must not finish the show.
 */
class ShowCompletionDecisionTest {
    private val show = WatchingContentRef(type = "series", id = "show")

    /** 22:58 of 43:49, the playhead from the device report (~52%). */
    private val reportedPositionMs = 22 * 60_000L + 58_000L
    private val reportedDurationMs = 43 * 60_000L + 49_000L

    private val season = listOf(
        releasedEpisode(1),
        releasedEpisode(2),
        releasedEpisode(3),
    )

    @Test
    fun `52 percent of the last episode does not finish the show`() {
        val lastEpisode = playback(
            episodeNumber = 3,
            positionMs = reportedPositionMs,
            durationMs = reportedDurationMs,
            isEnded = true,
            updatedAtEpochMs = 300L,
        )
        assertFalse(lastEpisode.isComplete)
        assertEquals(1_378_000L, reportedPositionMs)
        assertEquals(2_629_000L, reportedDurationMs)

        val action = showAction(
            samples = listOf(lastEpisode),
            watchedEpisodes = listOf(1 to 100L, 2 to 200L),
        )

        assertNotNull(action)
        assertEquals("Resume S1 E3", action.label)
        assertEquals("show:1:3", action.videoId)
        assertEquals(reportedPositionMs, action.resumePositionMs)
        assertFalse(showIsFinished(action))
        assertFalse(libraryMarksShowWatched(listOf(lastEpisode), watchedEpisodes = listOf(1, 2)))
        assertEquals(listOf("show:1:3"), continueWatchingVideoIds(lastEpisode))
        assertFalse(cascadesToWatchedHistory(lastEpisode))
    }

    @Test
    fun `last episode past the watched threshold finishes the show`() {
        val positionMs = reportedDurationMs * 9 / 10
        val lastEpisode = playback(
            episodeNumber = 3,
            positionMs = positionMs,
            durationMs = reportedDurationMs,
            isEnded = true,
            updatedAtEpochMs = 300L,
        )
        assertTrue(lastEpisode.isComplete)
        assertTrue(positionMs.toDouble() / reportedDurationMs.toDouble() >= 0.90)

        val action = showAction(
            samples = listOf(lastEpisode),
            watchedEpisodes = listOf(1 to 100L, 2 to 200L),
        )

        assertNull(action)
        assertTrue(showIsFinished(action))
        assertTrue(libraryMarksShowWatched(listOf(lastEpisode), watchedEpisodes = listOf(1, 2)))
        assertEquals(emptyList(), continueWatchingVideoIds(lastEpisode))
        assertTrue(cascadesToWatchedHistory(lastEpisode))
    }

    @Test
    fun `mid-season episode at 52 percent stays resume`() {
        val midEpisode = playback(
            episodeNumber = 2,
            positionMs = reportedPositionMs,
            durationMs = reportedDurationMs,
            isEnded = true,
            updatedAtEpochMs = 300L,
        )
        assertFalse(midEpisode.isComplete)

        val action = showAction(
            samples = listOf(midEpisode),
            watchedEpisodes = listOf(1 to 100L),
        )

        assertNotNull(action)
        assertEquals("Resume S1 E2", action.label)
        assertEquals("show:1:2", action.videoId)
        assertEquals(reportedPositionMs, action.resumePositionMs)
        assertFalse(showIsFinished(action))
        assertFalse(libraryMarksShowWatched(listOf(midEpisode), watchedEpisodes = listOf(1)))
        assertEquals(listOf("show:1:2"), continueWatchingVideoIds(midEpisode))
        assertFalse(cascadesToWatchedHistory(midEpisode))
    }

    private fun showIsFinished(action: WatchingSeriesPrimaryAction?): Boolean = action == null

    private fun showAction(
        samples: List<PlaybackSample>,
        watchedEpisodes: List<Pair<Int, Long>>,
    ): WatchingSeriesPrimaryAction? = decideSeriesPrimaryAction(
        content = show,
        episodes = season,
        progressRecords = samples.map { sample -> sample.toProgressRecord() },
        watchedRecords = watchedEpisodes.map { (episodeNumber, markedAtEpochMs) ->
            WatchingWatchedRecord(
                content = show,
                seasonNumber = 1,
                episodeNumber = episodeNumber,
                markedAtEpochMs = markedAtEpochMs,
            )
        },
        todayIsoDate = "2026-03-30",
        allowRewatch = false,
    )

    private fun libraryMarksShowWatched(
        samples: List<PlaybackSample>,
        watchedEpisodes: List<Int>,
    ): Boolean = hasWatchedAllMainSeasonEpisodes(
        episodes = season,
        todayIsoDate = "2026-03-30",
        isEpisodeWatched = { episode ->
            val episodeNumber = episode.episodeNumber
            episodeNumber in watchedEpisodes ||
                samples.any { sample ->
                    sample.episodeNumber == episodeNumber && sample.isComplete
                }
        },
    )

    private fun continueWatchingVideoIds(sample: PlaybackSample): List<String> =
        listOf(sample.toEntry()).continueWatchingEntries().map { it.videoId }

    private fun cascadesToWatchedHistory(sample: PlaybackSample): Boolean =
        shouldCascadeCompletedProgressToWatchedHistory(
            entry = sample.toEntry(),
            providerOwnsCompletedHistory = false,
        )

    private fun playback(
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long,
        isEnded: Boolean,
        updatedAtEpochMs: Long,
    ): PlaybackSample = PlaybackSample(
        episodeNumber = episodeNumber,
        positionMs = positionMs,
        durationMs = durationMs,
        isEnded = isEnded,
        updatedAtEpochMs = updatedAtEpochMs,
        isComplete = isProgressComplete(
            positionMs = positionMs,
            durationMs = durationMs,
            isEnded = isEnded,
        ),
    )

    private fun releasedEpisode(episodeNumber: Int): WatchingReleasedEpisode =
        WatchingReleasedEpisode(
            videoId = "ep$episodeNumber",
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            title = "Episode $episodeNumber",
            releasedDate = "2026-03-0$episodeNumber",
        )

    private inner class PlaybackSample(
        val episodeNumber: Int,
        val positionMs: Long,
        val durationMs: Long,
        val isEnded: Boolean,
        val updatedAtEpochMs: Long,
        val isComplete: Boolean,
    ) {
        fun toProgressRecord(): WatchingProgressRecord = WatchingProgressRecord(
            content = show,
            videoId = "show:1:$episodeNumber",
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            lastUpdatedEpochMs = updatedAtEpochMs,
            lastPositionMs = positionMs,
            isCompleted = isComplete,
        )

        fun toEntry(): WatchProgressEntry = WatchProgressEntry(
            contentType = "series",
            parentMetaId = show.id,
            parentMetaType = show.type,
            videoId = "show:1:$episodeNumber",
            title = "Show",
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            lastPositionMs = positionMs,
            durationMs = durationMs,
            lastUpdatedEpochMs = updatedAtEpochMs,
            isCompleted = isComplete,
        )
    }
}
