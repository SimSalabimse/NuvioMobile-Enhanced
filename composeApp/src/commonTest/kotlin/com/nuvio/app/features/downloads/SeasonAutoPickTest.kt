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
