package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamAutoPlayMode
import com.nuvio.app.features.streams.StreamAutoPlaySource
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonAutoPickTest {
    private fun settings(mode: StreamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM, regex: String = "") =
        SeasonAutoPickSettings(
            mode = mode,
            regexPattern = regex,
            source = StreamAutoPlaySource.ALL_SOURCES,
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = null,
            timeoutSeconds = 5,
            debridEnabled = true,
            activeResolverProviderId = null,
        )

    private fun direct(name: String, url: String) =
        StreamItem(name = name, url = url, addonName = "Addon", addonId = "addon")

    @Test
    fun picksTheSameStreamAsAutoPlay() {
        val streams = listOf(direct("720p", "https://a.example/1.mkv"), direct("1080p", "https://a.example/2.mkv"))
        assertEquals(streams[0], evaluateSeasonAutoPick(streams, settings(), setOf("Addon")).stream)
        val regex = evaluateSeasonAutoPick(streams, settings(StreamAutoPlayMode.REGEX_MATCH, "1080p"), setOf("Addon"))
        assertEquals(streams[1], regex.stream)
    }

    @Test
    fun queuedEpisodeGetsASourceWhenAutoPlayIsManual() {
        val streams = listOf(direct("1080p", "https://a.example/1.mkv"))
        assertNull(evaluateSeasonAutoPick(streams, settings(StreamAutoPlayMode.MANUAL), setOf("Addon")).stream)
        assertEquals(streams[0], pickWithoutReference(streams, settings(StreamAutoPlayMode.MANUAL), setOf("Addon")))
        assertNull(pickWithoutReference(emptyList(), settings(StreamAutoPlayMode.MANUAL), setOf("Addon")))
    }

    @Test
    fun noStreamsYetIsNotAGiveUp() {
        val result = evaluateSeasonAutoPick(emptyList(), settings(), emptySet())
        assertNull(result.stream)
        assertFalse(seasonAutoPickShouldGiveUp(result, allSourcesAnswered = false, hardTimeoutElapsed = false))
    }

    @Test
    fun givesUpOnlyWhenEverythingAnsweredOrHardTimeout() {
        val none = SeasonAutoPickResult(null, waitingForDebrid = false)
        val waiting = SeasonAutoPickResult(null, waitingForDebrid = true)
        assertFalse(seasonAutoPickShouldGiveUp(none, allSourcesAnswered = false, hardTimeoutElapsed = false))
        assertTrue(seasonAutoPickShouldGiveUp(none, allSourcesAnswered = true, hardTimeoutElapsed = false))
        assertFalse(seasonAutoPickShouldGiveUp(waiting, allSourcesAnswered = true, hardTimeoutElapsed = false))
        assertTrue(seasonAutoPickShouldGiveUp(waiting, allSourcesAnswered = true, hardTimeoutElapsed = true))
        val found = SeasonAutoPickResult(direct("x", "https://a.example/x.mkv"), waitingForDebrid = false)
        assertFalse(seasonAutoPickShouldGiveUp(found, allSourcesAnswered = true, hardTimeoutElapsed = true))
    }
}

class SeasonUnairedTest {
    private fun video(released: String?, available: Boolean = true) =
        com.nuvio.app.features.details.MetaVideo(id = "x", title = "t", released = released, available = available, season = 1, episode = 1)

    @Test
    fun onlyPastAirDatesAreDownloadable() {
        val now = 1_760_000_000_000L // 2025-10-09
        assertTrue(video("2025-01-01").hasAiredForDownload(now))
        assertFalse(video("2030-01-01").hasAiredForDownload(now))
        assertFalse(video(null).hasAiredForDownload(now))
        assertFalse(video("").hasAiredForDownload(now))
        assertFalse(video("2025-01-01", available = false).hasAiredForDownload(now))
    }

    @Test
    fun unairedLabelShowsDate() {
        assertEquals("Airs 12 Nov 2026", unairedLabel("2026-11-12T00:00:00.000Z"))
        assertEquals("Not aired yet", unairedLabel(null))
    }
}
