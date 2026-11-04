package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.player.IntroLengthAdvisory
import com.nuvio.app.features.player.MarkLimits
import com.nuvio.app.features.player.MarkProblem
import com.nuvio.app.features.player.introLengthAdvisory
import com.nuvio.app.features.player.markProblems
import com.nuvio.app.features.player.markSegmentLimits
import com.nuvio.app.features.player.skipFeedbackMaySubmit
import com.nuvio.app.features.player.skipFeedbackOwnLedger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TheIntroDbSubmitRulesTest {
    @Test
    fun durationOutsideFiveMinutesToSixHoursIsOmitted() {
        val short = payload(videoDurationMs = 120_000L, startSec = 10.0, endSec = 40.0, segment = "intro")
        assertFalse(short.containsKey("video_duration_ms"))
        val episode = payload(videoDurationMs = 2_700_000L, startSec = 10.0, endSec = 40.0, segment = "intro")
        assertEquals(2_700_000L, episode.getValue("video_duration_ms").jsonPrimitive.long)
    }

    @Test
    fun openCreditsEndAndNoneVoteOmitTimeKeys() {
        val credits = payload(segment = "credits", startSec = 1_200.0, endSec = null, videoDurationMs = 2_700_000L)
        assertEquals(1_200_000L, credits.getValue("start_ms").jsonPrimitive.long)
        assertFalse(credits.containsKey("end_ms"))
        val fromStart = payload(segment = "intro", startSec = 0.0, endSec = 40.0, videoDurationMs = 2_700_000L)
        assertFalse(fromStart.containsKey("start_ms"))
        assertEquals(40_000L, fromStart.getValue("end_ms").jsonPrimitive.long)
        val none = payload(segment = "intro", startSec = null, endSec = null, videoDurationMs = 2_700_000L)
        assertFalse(none.containsKey("start_ms"))
        assertFalse(none.containsKey("end_ms"))
        assertTrue(markProblems("credits", 1_200.0, null, 2_700.0).isEmpty())
        assertTrue(markProblems("intro", null, null, 2_700.0).isEmpty())
        assertEquals(listOf(MarkProblem.EndNotAfterStart), markProblems("intro", 20.0, 10.0, 2_700.0))
    }

    @Test
    fun duplicateIsAlreadySubmittedAndRateLimitBlocksTheNextCall() = runBlocking {
        TheIntroDb.resetSubmitGateForTest()
        try {
            var calls = 0
            val now = 5_000_000L
            val duplicate = TheIntroDb.submitTimestamp(
                apiKey = "test-key",
                tmdbId = 1396,
                type = "tv",
                segment = "intro",
                season = 1,
                episode = 2,
                startSec = 10.0,
                endSec = 40.0,
                videoDurationMs = 2_700_000L,
                nowEpochMs = now,
                transport = { _, _, _, _ ->
                    calls += 1
                    response(409, emptyMap())
                },
            )
            assertTrue(duplicate is TheIntroDbSubmitResult.AlreadySubmitted)
            assertNull(TheIntroDb.lastSubmitError)
            assertEquals(1, calls)

            calls = 0
            val limited = TheIntroDb.submitTimestamp(
                apiKey = "test-key",
                tmdbId = 1396,
                type = "tv",
                segment = "intro",
                season = 1,
                episode = 2,
                startSec = 10.0,
                endSec = 40.0,
                videoDurationMs = 2_700_000L,
                nowEpochMs = now,
                transport = { _, _, _, _ ->
                    calls += 1
                    response(429, mapOf("Retry-After" to "30"))
                },
            )
            check(limited is TheIntroDbSubmitResult.Failed)
            assertEquals(TheIntroDbRateLimitMessage, limited.message)
            assertEquals(1, calls)
            val blocked = TheIntroDb.submitTimestamp(
                apiKey = "test-key",
                tmdbId = 1396,
                type = "tv",
                segment = "intro",
                season = 1,
                episode = 2,
                startSec = 10.0,
                endSec = 40.0,
                videoDurationMs = 2_700_000L,
                nowEpochMs = now + 1_000L,
                transport = { _, _, _, _ ->
                    calls += 1
                    response(200, emptyMap())
                },
            )
            check(blocked is TheIntroDbSubmitResult.Failed)
            assertEquals(TheIntroDbRateLimitMessage, blocked.message)
            assertEquals(1, calls)
        } finally {
            TheIntroDb.resetSubmitGateForTest()
        }
    }

    @Test
    fun introWarnsAbove200SecondsAndBlocksAbove300() {
        assertEquals(IntroLengthAdvisory.Warn, introLengthAdvisory("intro", 200.1))
        assertTrue(markProblems("intro", 0.0, 200.1, 3_000.0).isEmpty())
        assertEquals(IntroLengthAdvisory.Block, introLengthAdvisory("intro", 300.1))
        assertEquals(listOf(MarkProblem.TooLong), markProblems("intro", 0.0, 300.1, 3_000.0))
        assertEquals(MarkLimits(5.0, 1_200.0), markSegmentLimits("recap"))
        assertEquals(MarkLimits(5.0, 1_800.0), markSegmentLimits("credits"))
        assertEquals(MarkLimits(5.0, 1_800.0), markSegmentLimits("preview"))
        assertEquals(MarkLimits(5.0, 300.0), markSegmentLimits("intro"))
    }

    @Test
    fun goodDoesNotSendAThreeSecondIntroAndRemembersAValidOne() {
        assertFalse(skipFeedbackMaySubmit("intro", 0.0, 3.0, 2_700.0))
        assertTrue(skipFeedbackMaySubmit("intro", 0.0, 40.0, 2_700.0))
        val remembered = skipFeedbackOwnLedger(
            entries = emptyMap(),
            contentKey = "tt0903747:1:2",
            segmentType = "intro",
            service = IntroSubmitService.THE_INTRODB,
            sent = true,
        )
        assertEquals(setOf("intro"), remembered["tt0903747:1:2"])
        assertEquals(
            emptyMap(),
            skipFeedbackOwnLedger(
                entries = emptyMap(),
                contentKey = "tt0903747:1:2",
                segmentType = "intro",
                service = IntroSubmitService.THE_INTRODB,
                sent = false,
            ),
        )
    }

    @Test
    fun tvdbEpisodeIdAndAbsoluteMappingAreOptional() {
        val withId = payload(
            segment = "intro",
            startSec = 10.0,
            endSec = 40.0,
            videoDurationMs = 2_700_000L,
            tvdbEpisodeId = 4242L,
        )
        assertEquals(4242L, withId.getValue("tvdb_episode_id").jsonPrimitive.long)
        assertFalse(withId.containsKey("mapping"))
        val plain = payload(segment = "intro", startSec = 10.0, endSec = 40.0, videoDurationMs = 2_700_000L)
        assertFalse(plain.containsKey("tvdb_episode_id"))
        assertFalse(plain.containsKey("mapping"))
        val kitsu = theIntroDbEpisodeFields(
            videoId = "kitsu:9",
            isMovie = false,
            season = 1,
            episode = 3,
            mappings = listOf(SimklIdResolver.EpisodeMapping(3, 1, 3, 4242L)),
        )
        assertEquals("absolute", kitsu.mapping)
        assertEquals(4242L, kitsu.tvdbEpisodeId)
        val show = theIntroDbEpisodeFields(
            videoId = "tt0903747:1:3",
            isMovie = false,
            season = 1,
            episode = 3,
            mappings = listOf(SimklIdResolver.EpisodeMapping(12, 1, 3, 4242L)),
        )
        assertNull(show.mapping)
        assertEquals(4242L, show.tvdbEpisodeId)
        val absoluteBody = payload(
            segment = "intro",
            startSec = 10.0,
            endSec = 40.0,
            videoDurationMs = 2_700_000L,
            tvdbEpisodeId = kitsu.tvdbEpisodeId,
            mapping = kitsu.mapping,
        )
        assertEquals("absolute", absoluteBody.getValue("mapping").jsonPrimitive.content)
    }

    private fun payload(
        segment: String,
        startSec: Double?,
        endSec: Double?,
        videoDurationMs: Long?,
        tvdbEpisodeId: Long? = null,
        mapping: String? = null,
    ) = Json.parseToJsonElement(
        checkNotNull(
            theIntroDbSubmitPayload(
                tmdbId = 1396,
                type = "tv",
                segment = segment,
                season = 1,
                episode = 2,
                startSec = startSec,
                endSec = endSec,
                videoDurationMs = videoDurationMs,
                tvdbEpisodeId = tvdbEpisodeId,
                mapping = mapping,
            ),
        ),
    ).jsonObject

    private fun response(status: Int, headers: Map<String, String>) = RawHttpResponse(
        status = status,
        statusText = status.toString(),
        url = TheIntroDb.SUBMIT_URL,
        body = "",
        headers = headers,
    )
}
