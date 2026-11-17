package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinueWatchingArtworkHostTest {
    @Test
    fun anEntryWithNoImageUsesTheCachedShowPoster() {
        val filled = blankItem().fillingBlankArtworkFrom(show(poster = "poster.jpg", background = null))
        assertEquals(listOf("poster.jpg"), chain(filled))
        assertEquals("poster.jpg", filled.poster)
        assertNull(filled.episodeThumbnail)
    }

    @Test
    fun aMissingEpisodeStillUsesTheBackdropThenThePoster() {
        val chain = continueWatchingArtworkChain(
            episodeStill = null,
            backdrop = "backdrop.jpg",
            poster = "poster.jpg",
            cachedStill = "",
        )
        assertEquals(listOf("backdrop.jpg", "poster.jpg"), chain)
    }

    @Test
    fun onlyAPosterIsUsedWhenNothingElseExists() {
        assertEquals(
            listOf("poster.jpg"),
            continueWatchingArtworkChain(episodeStill = null, backdrop = " ", poster = "poster.jpg"),
        )
    }

    @Test
    fun aFailedStillFallsThroughTheChain() {
        val chain = continueWatchingArtworkChain(
            episodeStill = "still.jpg",
            backdrop = "backdrop.jpg",
            poster = "poster.jpg",
        )
        assertEquals("backdrop.jpg", chain.drop(1).first())
        assertEquals(listOf("still.jpg", "backdrop.jpg", "poster.jpg"), chain)
    }

    @Test
    fun aCachedEmptyStillIsBypassedOnceArtworkCanExist() {
        val meta = show(poster = "poster.jpg", still = null, released = "2026-11-13")
        assertTrue(
            shouldBypassCachedMetaForMissingArtwork(
                meta = meta,
                todayIso = "2026-11-13",
                lastAttemptMs = 0L,
                nowMs = 5_000L,
                offline = false,
            ),
        )
        assertFalse(
            shouldBypassCachedMetaForMissingArtwork(
                meta = meta,
                todayIso = "2026-11-13",
                lastAttemptMs = 5_000L,
                nowMs = 5_000L + 60_000L,
                offline = false,
            ),
        )
        assertFalse(
            shouldBypassCachedMetaForMissingArtwork(
                meta = meta,
                todayIso = "2026-11-13",
                lastAttemptMs = 0L,
                nowMs = 5_000L,
                offline = true,
            ),
        )
        val withStill = show(poster = "poster.jpg", still = "still.jpg", released = "2026-11-13")
        assertFalse(
            shouldBypassCachedMetaForMissingArtwork(
                meta = withStill,
                todayIso = "2026-11-13",
                lastAttemptMs = 0L,
                nowMs = 5_000L,
                offline = false,
            ),
        )
    }

    @Test
    fun anAiredNextUpRowWithNoStillIsResolvedAgain() {
        assertTrue(
            cachedNextUpStillNeedsArtwork(
                released = "2026-11-13",
                episodeThumbnail = null,
                seasonNumber = 1,
                episodeNumber = 8,
                todayIso = "2026-11-13",
            ),
        )
        assertTrue(
            cachedNextUpStillNeedsArtwork(
                released = "2026-11-13",
                episodeThumbnail = "  ",
                seasonNumber = 1,
                episodeNumber = 8,
                todayIso = "2026-11-13",
            ),
        )
        assertFalse(
            cachedNextUpStillNeedsArtwork(
                released = "2026-11-14",
                episodeThumbnail = null,
                seasonNumber = 1,
                episodeNumber = 8,
                todayIso = "2026-11-13",
            ),
        )
        assertFalse(
            cachedNextUpStillNeedsArtwork(
                released = "2026-11-13",
                episodeThumbnail = "still.jpg",
                seasonNumber = 1,
                episodeNumber = 8,
                todayIso = "2026-11-13",
            ),
        )
    }

    @Test
    fun anEmptyCachedStillIsAMissAndAiredArtworkIsRefetched() {
        val meta = show(poster = "poster.jpg", still = null, released = "2026-11-13")
        assertTrue(metaHasAiredEpisodeWithoutStill(meta, todayIso = "2026-11-13"))
        assertFalse(metaHasAiredEpisodeWithoutStill(meta, todayIso = "2026-11-12"))
        assertTrue(missingArtworkRetryDue(lastAttemptMs = 0L, nowMs = 1L))
        assertFalse(missingArtworkRetryDue(lastAttemptMs = 1_000L, nowMs = 1_000L + 60_000L))
        assertTrue(
            missingArtworkRetryDue(
                lastAttemptMs = 1_000L,
                nowMs = 1_000L + MISSING_ARTWORK_RETRY_MS,
            ),
        )
    }

    private fun chain(item: ContinueWatchingItem) = continueWatchingArtworkChain(
        episodeStill = item.episodeThumbnail,
        backdrop = item.background,
        poster = item.poster,
        imageUrl = item.imageUrl,
    )

    private fun blankItem() = ContinueWatchingItem(
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:1:8",
        title = "Show",
        subtitle = "S1 E8",
        imageUrl = null,
        seasonNumber = 1,
        episodeNumber = 8,
        resumePositionMs = 10_000L,
        durationMs = 100_000L,
        progressFraction = 0.1f,
    )

    private fun show(
        poster: String?,
        still: String? = null,
        released: String? = "2026-11-13",
        background: String? = "backdrop.jpg",
    ) = MetaDetails(
        id = "tt1",
        type = "series",
        name = "Show",
        poster = poster,
        background = background,
        videos = listOf(
            MetaVideo(
                id = "tt1:1:8",
                title = "Eight",
                season = 1,
                episode = 8,
                thumbnail = still,
                released = released,
            ),
        ),
    )
}
