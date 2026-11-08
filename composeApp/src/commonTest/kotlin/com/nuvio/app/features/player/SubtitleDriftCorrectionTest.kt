package com.nuvio.app.features.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [SubtitleDriftCorrection.correct], the function both playback sync entries call.
 * Match data is injected at that seam, or produced by [SubtitleAutoSyncEngine] through [matchObservation].
 * The test never calls the bridge setter itself.
 */
class SubtitleDriftCorrectionTest {
    @Test
    fun offsetAndDriftFromTheMatcherSetsSpeedAndDelay() {
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val points = listOf(
            20_000L to -5_000L,
            620_000L to -6_000L,
            1_220_000L to -7_000L,
            1_820_000L to -8_000L,
        )
        val observations = points.map { (center, lag) ->
            val observed = observationAt(center, lag)
            assertTrue(observed.confident, "matcher at $center returned $observed")
            assertTrue(abs(observed.offsetMs - lag) <= 400, "offset ${observed.offsetMs} lag $lag")
            observed
        }
        val ticket = correction.begin()
        val outcome = correction.correct(
            source = SubtitleObservationSource { observations },
            ticket = ticket,
            provider = "OpenSubtitles",
            release = "https://example.test/file.srt",
        )
        assertEquals(SubtitleDriftOutcome.Applied, outcome)
        val speed = transport.commands.last().getValue("sub-speed")
        assertTrue(speed < 1.0, "speed $speed")
        assertTrue(transport.commands.last().containsKey("sub-delay"))
        val export = SubtitleSyncExportLog.exportText()
        assertTrue(export.contains("provider=OpenSubtitles"))
        assertTrue(export.contains("confident=true"))
        assertTrue(!export.contains("http"))
        assertTrue(!export.contains("://"))
    }

    @Test
    fun lowConfidenceDoesNotApplyAFit() {
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        val outcome = correction.correct(
            source = SubtitleObservationSource {
                listOf(
                    SubtitleMatchObservation(60_000L, 2_000L, confident = false),
                    SubtitleMatchObservation(120_000L, 9_000L, confident = false),
                )
            },
            ticket = ticket,
            provider = null,
            release = null,
        )
        assertEquals(SubtitleDriftOutcome.LowConfidence, outcome)
        assertTrue(transport.fits.isEmpty())
        assertTrue(SubtitleSyncExportLog.exportText().contains("confident=false"))
    }

    @Test
    fun manualDelayIsAppliedOnceSeekDoesNothingAndRepeatStaysTheSame() {
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val source = SubtitleObservationSource {
            listOf(
                SubtitleMatchObservation(0L, 0L, confident = true),
                SubtitleMatchObservation(1_200_000L, 2_000L, confident = true),
            )
        }
        val ticket = correction.begin()
        assertEquals(SubtitleDriftOutcome.Applied, correction.correct(source, ticket, null, null))
        correction.noteManualDelay(250L)
        correction.noteManualDelay(250L)
        val once = transport.commands.last().getValue("sub-delay")
        correction.noteSeek()
        assertEquals(once, transport.commands.last().getValue("sub-delay"))
        val beforeRepeat = transport.fits.size
        val again = correction.begin()
        assertEquals(SubtitleDriftOutcome.Applied, correction.correct(source, again, null, null))
        assertEquals(beforeRepeat + 1, transport.fits.size)
        assertEquals(once, transport.commands.last().getValue("sub-delay"))
        assertTrue(transport.users.all { it == 250L })
    }

    @Test
    fun trackChangeClearsTheFitAndKeepsTheManualDelayOnce() {
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        correction.correct(
            source = SubtitleObservationSource {
                listOf(
                    SubtitleMatchObservation(0L, 0L, confident = true),
                    SubtitleMatchObservation(1_200_000L, 4_000L, confident = true),
                )
            },
            ticket = ticket,
            provider = null,
            release = null,
        )
        correction.noteManualDelay(250L)
        correction.noteTrackOrStreamChanged()
        val last = transport.commands.last()
        assertEquals(1.0, last.getValue("sub-speed"))
        assertEquals(0.25, last.getValue("sub-delay"))
    }

    @Test
    fun aConfidentZeroGoesThroughThePlaybackEntryAndClearsThePreviousSpeed() {
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val first = correction.begin()
        assertEquals(
            SubtitleDriftOutcome.Applied,
            correction.correct(
                source = SubtitleObservationSource {
                    listOf(
                        SubtitleMatchObservation(0L, 0L, confident = true),
                        SubtitleMatchObservation(1_200_000L, 4_000L, confident = true),
                    )
                },
                ticket = first,
                provider = "OpenSubtitles",
                release = "Episode.srt",
            ),
        )
        assertTrue(abs(transport.commands.last().getValue("sub-speed") - 1.0) > 0.001)
        correction.noteManualDelay(250L)
        val ticket = correction.begin()
        val outcome = applyCapturedSync(
            correction = correction,
            result = SubtitleAutoSyncResult.Success(offsetMs = 0, confidence = 0.8, rivalOffsetMs = 0),
            videoMs = 30_000L,
            ticket = ticket,
            provider = "OpenSubtitles",
            release = "Episode.srt",
            automatic = false,
        )
        assertEquals(SubtitleDriftOutcome.Applied, outcome)
        val last = transport.commands.last()
        assertEquals(1.0, last.getValue("sub-speed"))
        assertEquals(0.25, last.getValue("sub-delay"))
    }

    @Test
    fun aSearchWallOffsetIsNotAnAnchor() {
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        val outcome = applyCapturedSync(
            correction = correction,
            result = SubtitleAutoSyncResult.Success(offsetMs = -10_000, confidence = 0.9, rivalOffsetMs = 0),
            videoMs = 480_000L,
            ticket = ticket,
            provider = null,
            release = null,
            automatic = false,
        )
        assertEquals(SubtitleDriftOutcome.LowConfidence, outcome)
        assertTrue(transport.fits.isEmpty())
    }

    @Test
    fun manualDelayDuringCollectionCancelsAnAutomaticFit() {
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val observations = listOf(
            SubtitleMatchObservation(0L, 0L, confident = true),
            SubtitleMatchObservation(1_200_000L, 4_000L, confident = true),
        )
        val during = correction.begin()
        val duringOutcome = correction.correct(
            source = SubtitleObservationSource {
                correction.noteManualDelay(250L)
                observations
            },
            ticket = during,
            provider = null,
            release = null,
            automatic = true,
        )
        assertEquals(SubtitleDriftOutcome.Cancelled, duringOutcome)
        assertTrue(transport.fits.isEmpty())
        assertEquals(0.25, transport.commands.last().getValue("sub-delay"))

        val between = correction.begin()
        correction.noteManualDelay(250L)
        val betweenOutcome = correction.correct(
            source = SubtitleObservationSource { observations },
            ticket = between,
            provider = null,
            release = null,
            automatic = true,
        )
        assertEquals(SubtitleDriftOutcome.Cancelled, betweenOutcome)
        assertTrue(transport.fits.isEmpty())

        val requested = correction.begin()
        val applied = correction.correct(
            source = SubtitleObservationSource { observations },
            ticket = requested,
            provider = null,
            release = null,
            automatic = false,
        )
        assertEquals(SubtitleDriftOutcome.Applied, applied)
        assertEquals(1, transport.fits.size)
        val fitDelaySeconds = transport.fits.last().second / 1_000.0
        assertEquals(fitDelaySeconds + 0.25, transport.commands.last().getValue("sub-delay"), 0.001)
        assertTrue(transport.users.all { it == 250L })
    }

    @Test
    fun aPlayerWithoutRateSupportDoesNotClaimASpeedFit() {
        val transport = RecordingTransport(supportsSubtitleRate = false)
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        val outcome = correction.correct(
            source = SubtitleObservationSource {
                listOf(
                    SubtitleMatchObservation(0L, 0L, confident = true),
                    SubtitleMatchObservation(1_200_000L, 8_000L, confident = true),
                )
            },
            ticket = ticket,
            provider = "OpenSubtitles",
            release = "Episode.srt",
        )
        assertEquals(SubtitleDriftOutcome.RateUnsupported, outcome)
        assertTrue(transport.fits.isEmpty())
        val export = SubtitleSyncExportLog.exportText()
        assertTrue(export.contains("rateSupported=false"))
        assertTrue(export.contains("confident=false"))
        assertTrue(export.contains("subSpeed=1.0"))
    }

    @Test
    fun aCancelledTicketDoesNotApply() {
        val transport = RecordingTransport()
        val correction = SubtitleDriftCorrection(transport)
        val ticket = correction.begin()
        correction.cancel()
        val outcome = correction.correct(
            source = SubtitleObservationSource {
                listOf(
                    SubtitleMatchObservation(0L, 1_000L, confident = true),
                    SubtitleMatchObservation(600_000L, 3_000L, confident = true),
                )
            },
            ticket = ticket,
            provider = null,
            release = null,
        )
        assertEquals(SubtitleDriftOutcome.Cancelled, outcome)
        assertTrue(transport.fits.isEmpty())
    }

    /** Same cue mask the aligner already accepts, moved to [center] and lagged by [lagMs]. */
    private fun observationAt(center: Long, lagMs: Long): SubtitleMatchObservation {
        val shift = center - 20_000L
        val cues = listOf(
            8_000L to 10_000L,
            12_000L to 14_500L,
            17_000L to 19_000L,
            22_000L to 25_000L,
        ).map { (start, end) -> SubtitleSyncCue(start + shift, end + shift, "line") }
        val speech = cues.map { it.startTimeMs + lagMs to it.endTimeMs + lagMs }
        val audio = mask(speech, (center - 20_000L).coerceAtLeast(0L), center + 20_000L)
        return matchObservation(center, audio, cues)
            ?: error("matcher returned no observation at $center")
    }

    private fun mask(spans: List<Pair<Long, Long>>, fromMs: Long, toMs: Long): List<AudioEnergySample> {
        val samples = ArrayList<AudioEnergySample>()
        var time = fromMs
        while (time <= toMs) {
            val speaking = spans.any { (start, end) -> time >= start && time < end }
            samples.add(AudioEnergySample(timestampMs = time, energy = if (speaking) 0.5 else 0.02))
            time += 100L
        }
        return samples
    }
}

private class RecordingTransport(
    override val supportsSubtitleRate: Boolean = true,
) : SubtitleSyncTransport {
    val fits = mutableListOf<Pair<Double, Long>>()
    val users = mutableListOf<Long>()
    val commands = mutableListOf<Map<String, Double>>()
    private var speed = 1.0
    private var fitDelay = 0L
    private var user = 0L

    override fun applyFit(speed: Double, fitDelayMs: Long) {
        fits += speed to fitDelayMs
        this.speed = speed
        fitDelay = fitDelayMs
        publish()
    }

    override fun applyUserDelay(delayMs: Long) {
        users += delayMs
        user = delayMs
        publish()
    }

    override fun resetFit() {
        speed = 1.0
        fitDelay = 0L
        publish()
    }

    private fun publish() {
        commands += mpvPropertyValues(
            withUserDelay(MpvSubtitleProperties(speed, fitDelay, confident = true), user),
        )
    }
}
