package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

class SeasonDownloadTest {
    @Test
    fun `known sizes add up and unknown sizes stay out of the sum`() {
        val summary = summarizeSeasonSizes(
            listOf(1_000L, null, 2_500L, 0L),
        )

        assertEquals(4, summary.selectedCount)
        assertEquals(3_500L, summary.knownBytes)
        assertEquals(2, summary.unknownCount)
    }

    @Test
    fun `a mixed selection totals as at least the known bytes`() {
        val summary = summarizeSeasonSizes(listOf(1_500_000_000L, null))

        assertEquals(
            "2 selected · at least 1.3 GB",
            seasonSelectionTotalText(summary, ::formatSeasonBytes),
        )
    }

    @Test
    fun `a selection with no known size does not invent a total`() {
        val summary = summarizeSeasonSizes(listOf(null, null))

        assertEquals(0L, summary.knownBytes)
        assertEquals(
            "2 selected · Size unknown",
            seasonSelectionTotalText(summary, ::formatSeasonBytes),
        )
    }

    @Test
    fun `every known size is the exact total`() {
        val summary = summarizeSeasonSizes(listOf(2_000L, 3_000L))

        assertEquals(
            "2 selected · 5000 B",
            seasonSelectionTotalText(summary) { "$it B" },
        )
    }

    @Test
    fun `binge group matches before addon and quality`() {
        val choice = SeasonSourceChoice(anchor())
        val otherRelease = stream(
            name = "1080p",
            addonId = "addon:other",
            bingeGroup = "release-b",
            url = "https://cdn.example/other.mkv",
        )
        val sameRelease = stream(
            name = "720p",
            addonId = "addon:other",
            bingeGroup = "release-a",
            url = "https://cdn.example/same.mkv",
        )

        val match = matchSeasonStream(choice, listOf(otherRelease, sameRelease))

        assertIs<SeasonStreamMatch.Matched>(match)
        assertEquals("https://cdn.example/same.mkv", match.stream.playableDirectUrl)
    }

    @Test
    fun `the same addon and quality label match when binge group is missing`() {
        val choice = SeasonSourceChoice(
            addonId = "addon:torrentio",
            qualityLabel = "1080p",
            bingeGroup = null,
        )
        val wrongQuality = stream(
            name = "720p",
            addonId = "addon:torrentio",
            bingeGroup = null,
            url = "https://cdn.example/720.mkv",
        )
        val right = stream(
            name = "Show.S01E02.1080p.mkv",
            addonId = "addon:torrentio",
            bingeGroup = null,
            url = "https://cdn.example/1080.mkv",
        )

        val match = matchSeasonStream(choice, listOf(wrongQuality, right))

        assertIs<SeasonStreamMatch.Matched>(match)
        assertEquals(right.url, match.stream.url)
    }

    @Test
    fun `a row with no match stays unmatched and says why`() {
        val choice = SeasonSourceChoice(anchor())
        val other = stream(
            name = "720p",
            addonId = "addon:else",
            bingeGroup = "release-b",
            url = "https://cdn.example/else.mkv",
        )

        val match = matchSeasonStream(choice, listOf(other))

        assertEquals(
            SeasonStreamMatch.Unmatched(SeasonMatchFailure.NoMatchingSource),
            match,
        )
        assertEquals(
            SeasonStreamMatch.Unmatched(SeasonMatchFailure.NoStreams),
            matchSeasonStream(choice, emptyList()),
        )
    }

    @Test
    fun `hls dash and magnets stay rejected`() {
        val choice = SeasonSourceChoice(
            addonId = "addon:torrentio",
            qualityLabel = "1080p",
            bingeGroup = "release-a",
        )
        val hls = stream(name = "1080p", addonId = "addon:torrentio", bingeGroup = "release-a", url = "https://cdn.example/ep.m3u8")
        val dash = stream(name = "1080p", addonId = "addon:torrentio", bingeGroup = "release-a", url = "https://cdn.example/ep.mpd")
        val magnet = stream(name = "1080p", addonId = "addon:torrentio", bingeGroup = "release-a", url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")

        assertEquals(SeasonMatchFailure.UnsupportedFormat, (matchSeasonStream(choice, listOf(hls)) as SeasonStreamMatch.Unmatched).reason)
        assertEquals(SeasonMatchFailure.UnsupportedFormat, (matchSeasonStream(choice, listOf(dash)) as SeasonStreamMatch.Unmatched).reason)
        val otherRelease = stream(
            name = "1080p",
            addonId = "addon:torrentio",
            bingeGroup = null,
            url = "https://cdn.example/other.mkv",
        )
        assertEquals(
            SeasonMatchFailure.UnsupportedFormat,
            (matchSeasonStream(choice, listOf(hls, otherRelease)) as SeasonStreamMatch.Unmatched).reason,
        )
        assertTrue(!hls.isSeasonDownloadCandidate())
        assertTrue(!dash.isSeasonDownloadCandidate())
        assertEquals(null, magnet.playableDirectUrl)
        assertTrue(!magnet.url!!.isSupportedDownloadUrl())
    }

    @Test
    fun `a known size uses behavior hints and a missing size can be probed`() {
        val hinted = stream(name = "1080p", addonId = "addon:torrentio", bingeGroup = "release-a", url = "https://cdn.example/a.mkv", videoSize = 42L)
        assertEquals(42L, hinted.behaviorHints.videoSize)
        assertEquals(
            99L,
            remoteContentLengthBytes(
                statusCode = 206,
                headers = mapOf("Content-Range" to listOf("bytes 0-0/99")),
                requestWasRanged = true,
            ),
        )
        assertEquals(
            null,
            remoteContentLengthBytes(
                statusCode = 206,
                headers = mapOf("Content-Length" to listOf("1")),
                requestWasRanged = true,
            ),
        )
    }

    @Test
    fun `size probes stay at three at a time`() = runTest {
        assertEquals(3, SEASON_SIZE_PROBE_CONCURRENCY)
        val gate = Mutex()
        var inFlight = 0
        var maxInFlight = 0
        val targets = (1..6).map { SeasonProbeTarget(id = "e$it", url = "https://cdn.example/$it") }

        probeSeasonSizes(
            targets = targets,
            probe = {
                gate.withLock {
                    inFlight += 1
                    if (inFlight > maxInFlight) maxInFlight = inFlight
                }
                delay(20)
                gate.withLock { inFlight -= 1 }
                10L
            },
            onResult = { _, _ -> },
        )

        assertEquals(3, maxInFlight)
    }
}

private fun anchor(): StreamItem = stream(
    name = "1080p",
    addonId = "addon:torrentio",
    bingeGroup = "release-a",
    url = "https://cdn.example/e1.mkv",
)

private fun stream(
    name: String,
    addonId: String,
    bingeGroup: String?,
    url: String,
    videoSize: Long? = null,
): StreamItem = StreamItem(
    name = name,
    url = url,
    addonName = addonId.substringAfter(':'),
    addonId = addonId,
    behaviorHints = StreamBehaviorHints(
        bingeGroup = bingeGroup,
        videoSize = videoSize,
    ),
)
