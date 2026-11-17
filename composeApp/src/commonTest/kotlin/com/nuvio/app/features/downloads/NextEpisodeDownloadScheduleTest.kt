package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class NextEpisodeDownloadScheduleTest {
    @Test
    fun constantsMatchTheSchedule() {
        assertEquals(0.20, NEXT_EPISODE_DOWNLOAD_SAFETY_MARGIN)
        assertEquals(1_000_000.0, CONSERVATIVE_DEFAULT_DOWNLOAD_BYTES_PER_SECOND)
        assertEquals(2_000_000_000L, NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES)
    }

    @Test
    fun sizeUsesKnownBytesThenBitrateTimesDurationThenTheStandIn() {
        val known = nextEpisodeExpectedBytes(knownBytes = 100L, bitrateBitsPerSecond = 8_000_000.0, durationMs = 3_600_000L)
        assertEquals(NextEpisodeSizeBasis.KnownBytes, known.basis)
        assertEquals(100L, known.bytes)

        val fromBitrate = nextEpisodeExpectedBytes(knownBytes = null, bitrateBitsPerSecond = 8_000_000.0, durationMs = 3_600_000L)
        assertEquals(NextEpisodeSizeBasis.BitrateTimesDuration, fromBitrate.basis)
        assertEquals(3_600_000_000L, fromBitrate.bytes)

        val missingDuration = nextEpisodeExpectedBytes(knownBytes = null, bitrateBitsPerSecond = 8_000_000.0, durationMs = null)
        assertEquals(NextEpisodeSizeBasis.UnknownFallback, missingDuration.basis)
        assertEquals(NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES, missingDuration.bytes)

        val unknown = nextEpisodeExpectedBytes(knownBytes = null, bitrateBitsPerSecond = null, durationMs = 3_600_000L)
        assertEquals(NextEpisodeSizeBasis.UnknownFallback, unknown.basis)
        assertEquals(NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES, unknown.bytes)
    }

    @Test
    fun stateTable() {
        val rows = listOf(
            Row("all gates open, early, fast", open(), StartAt(3_480_000L)),
            Row("position at the start moment", open(positionMs = 3_480_000L), StartNow),
            Row("position late", open(positionMs = 3_500_000L), StartNow),
            Row("playback 1.5x starts earlier", open(playbackSpeed = 1.5), StartAt(3_420_000L)),
            Row("speed unknown uses the conservative default", open(measuredBytesPerSecond = null), StartAt(3_000_000L)),
            Row("speed fast waits", open(measuredBytesPerSecond = 5_000_000.0), StartAt(3_480_000L)),
            Row("speed slow cannot finish, start at play", open(measuredBytesPerSecond = 100_000.0), StartNow),
            Row("slower than the fast row, start moves earlier", open(measuredBytesPerSecond = 2_000_000.0), StartAt(3_300_000L)),
            Row("size unknown uses the 2 GB stand-in", open(knownBytes = null), StartAt(3_120_000L)),
            Row(
                "size from bitrate times duration",
                open(knownBytes = null, bitrateBitsPerSecond = 8_000_000.0, nextEpisodeDurationMs = 3_600_000L, measuredBytesPerSecond = 10_000_000.0),
                StartAt(3_168_000L),
            ),
            Row("no safety margin starts later", open(safetyMargin = 0.0), StartAt(3_500_000L)),
            Row(
                "large file does not wait until the last eight minutes",
                open(durationMs = 43 * 60_000L, knownBytes = 2_000_000_000L, measuredBytesPerSecond = 2_000_000.0),
                StartAt(1_380_000L),
            ),
            Row(
                "old eight-minute mark is already late for that large file",
                open(durationMs = 43 * 60_000L, positionMs = 2_100_000L, knownBytes = 2_000_000_000L, measuredBytesPerSecond = 2_000_000.0),
                StartNow,
            ),
            Row("wifi off", open(onWifi = false), Skip(NextEpisodeDownloadSkip.NotOnWifi)),
            Row("wifi on", open(onWifi = true), StartAt(3_480_000L)),
            Row("low data on wifi", open(lowDataOrExpensive = true), Skip(NextEpisodeDownloadSkip.LowDataOrExpensive)),
            Row("low data off", open(lowDataOrExpensive = false), StartAt(3_480_000L)),
            Row("cellular wins over low data", open(onWifi = false, lowDataOrExpensive = true), Skip(NextEpisodeDownloadSkip.NotOnWifi)),
            Row("free space short", open(freeBytes = 999_999_999L), Skip(NextEpisodeDownloadSkip.NotEnoughSpace)),
            Row("free space enough", open(freeBytes = 1_000_000_000L), StartAt(3_480_000L)),
            Row("free space unknown", open(freeBytes = null), StartAt(3_480_000L)),
            Row("manual download active", open(manualDownloadActiveOrQueued = true), Skip(NextEpisodeDownloadSkip.ManualDownloadActive)),
            Row("no manual download", open(manualDownloadActiveOrQueued = false), StartAt(3_480_000L)),
            Row("next already downloaded", open(nextAlreadyDownloaded = true), Skip(NextEpisodeDownloadSkip.AlreadyDownloaded)),
            Row("next not downloaded", open(nextAlreadyDownloaded = false), StartAt(3_480_000L)),
            Row("next already queued", open(nextAlreadyQueued = true), Skip(NextEpisodeDownloadSkip.AlreadyQueued)),
            Row("next not queued", open(nextAlreadyQueued = false), StartAt(3_480_000L)),
            Row("next has not aired", open(nextHasAired = false), Skip(NextEpisodeDownloadSkip.NotAired)),
            Row("next has aired", open(nextHasAired = true), StartAt(3_480_000L)),
            Row("no next episode", open(hasNextEpisode = false), Skip(NextEpisodeDownloadSkip.NoNextEpisode)),
            Row("not aired wins over an unknown runtime", open(nextHasAired = false, durationMs = 0L), Skip(NextEpisodeDownloadSkip.NotAired)),
            Row("runtime still unknown", open(durationMs = 0L), Skip(NextEpisodeDownloadSkip.RuntimeUnknown)),
            Row("placeholder runtime", open(durationMs = 60_000L), Skip(NextEpisodeDownloadSkip.RuntimeUnknown)),
        )
        rows.forEach { row ->
            assertEquals(row.expected, decideNextEpisodeDownload(row.facts), row.name)
        }
    }

    private data class Row(
        val name: String,
        val facts: NextEpisodeDownloadFacts,
        val expected: NextEpisodeDownloadDecision,
    )
}

private fun open(
    durationMs: Long = 3_600_000L,
    positionMs: Long = 0L,
    playbackSpeed: Double = 1.0,
    hasNextEpisode: Boolean = true,
    nextHasAired: Boolean = true,
    knownBytes: Long? = 500_000_000L,
    bitrateBitsPerSecond: Double? = null,
    nextEpisodeDurationMs: Long? = null,
    measuredBytesPerSecond: Double? = 5_000_000.0,
    onWifi: Boolean = true,
    lowDataOrExpensive: Boolean = false,
    freeBytes: Long? = 50_000_000_000L,
    manualDownloadActiveOrQueued: Boolean = false,
    nextAlreadyDownloaded: Boolean = false,
    nextAlreadyQueued: Boolean = false,
    safetyMargin: Double = NEXT_EPISODE_DOWNLOAD_SAFETY_MARGIN,
) = NextEpisodeDownloadFacts(
    durationMs = durationMs,
    positionMs = positionMs,
    playbackSpeed = playbackSpeed,
    hasNextEpisode = hasNextEpisode,
    nextHasAired = nextHasAired,
    knownSizeBytes = knownBytes,
    bitrateBitsPerSecond = bitrateBitsPerSecond,
    nextEpisodeDurationMs = nextEpisodeDurationMs,
    measuredDownloadBytesPerSecond = measuredBytesPerSecond,
    onWifi = onWifi,
    lowDataOrExpensive = lowDataOrExpensive,
    freeBytes = freeBytes,
    manualDownloadActiveOrQueued = manualDownloadActiveOrQueued,
    nextAlreadyDownloaded = nextAlreadyDownloaded,
    nextAlreadyQueued = nextAlreadyQueued,
    safetyMargin = safetyMargin,
)

private typealias StartAt = NextEpisodeDownloadDecision.StartAt
private typealias StartNow = NextEpisodeDownloadDecision.StartNow
private typealias Skip = NextEpisodeDownloadDecision.Skip
