package com.nuvio.app.features.analysis

import com.nuvio.app.features.player.skip.SkipInterval
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SiblingClipReferenceTest {
    private val ref = SiblingClipReference

    @Test
    fun nearestEpisodeFirstEarlierWinsTie() {
        assertEquals(listOf(4, 6, 3, 8), ref.nearestFirst(5, listOf(8, 3, 6, 5, 4)))
    }

    @Test
    fun ownMarkBeatsOnlineWithoutLookups() = runTest {
        var lookups = 0
        val picked = ref.pickReference(
            target = 5,
            own = listOf(SiblingTimes(9, 1_000L to 60_000L), SiblingTimes(2, 2_000L to 50_000L)),
            episodes = (1..10).toList(),
        ) { lookups++; null }
        assertEquals(2, picked?.episode)
        assertEquals(0, lookups)
    }

    @Test
    fun onlineLookupsNearestFirstAndCapped() = runTest {
        val asked = mutableListOf<Int>()
        val none = ref.pickReference(5, emptyList(), (1..10).toList()) { asked += it; null }
        assertNull(none)
        assertEquals(listOf(4, 6, 3, 7), asked)
        val found = ref.pickReference(5, emptyList(), (1..10).toList()) { if (it == 6) SiblingTimes(6, 0L to 1L) else null }
        assertEquals(6, found?.episode)
    }

    @Test
    fun onlineTimesKeepOnlyTrustedProvidersAndNeedAnIntro() {
        val times = ref.onlineTimes(
            listOf(
                SkipInterval(10.0, 20.0, "intro", "aniskip"),
                SkipInterval(30.0, 90.0, "intro", "theintrodb"),
                SkipInterval(1200.0, 1290.0, "credits", "introdb"),
            ),
            episode = 3,
        )
        assertEquals(SiblingTimes(3, 30_000L to 90_000L, 1_200_000L to 1_290_000L), times)
        assertNull(ref.onlineTimes(listOf(SkipInterval(1200.0, 1290.0, "credits", "theintrodb")), 3))
    }

    @Test
    fun locateShiftsTheSiblingSegmentByTheMatchOffset() {
        val random = Random(7)
        val episode = IntArray(3_000) { random.nextInt() }
        // The sibling's intro is at 40-100 s; the same audio sits at 70-130 s of this episode.
        val clipStartMs = 35_000L
        val clip = IntArray(700) { i ->
            val inIntro = i in 50 until 650
            if (inIntro) episode[700 + (i - 50)] else random.nextInt()
        }
        assertEquals(70_000L to 130_000L, ref.locate(episode, 0L, clip, clipStartMs, 100L, 40_000L to 100_000L))
    }

    @Test
    fun aFortySecondOpeningKeepsFortySecondsOfASeventySecondOne() {
        val random = Random(21)
        val shared = IntArray(400) { random.nextInt() }
        val clip = shared + IntArray(300) { random.nextInt() }
        val episode = IntArray(3_000) { random.nextInt() }
        shared.copyInto(episode, destinationOffset = 100)
        assertEquals(
            10_000L to 50_000L,
            ref.locate(
                episode,
                0L,
                clip,
                0L,
                100L,
                0L to 70_000L,
                targetDurationMs = 1_440_000L,
                referenceDurationMs = 1_470_000L,
            ),
        )
        val short = IntArray(300) { Random(23).nextInt() }
        val shortEpisode = IntArray(3_000) { Random(24).nextInt() }
        short.copyInto(shortEpisode, destinationOffset = 100)
        assertNull(
            ref.locate(
                shortEpisode,
                0L,
                short + IntArray(400) { Random(25).nextInt() },
                0L,
                100L,
                0L to 70_000L,
                targetDurationMs = 1_440_000L,
                referenceDurationMs = 1_440_000L,
            ),
        )
    }

    @Test
    fun anExtendedCutIsNotTransferredOntoTheBroadcastCut() {
        val random = Random(22)
        val shared = IntArray(600) { random.nextInt() }
        val episode = IntArray(3_000) { random.nextInt() }
        shared.copyInto(episode, destinationOffset = 100)
        val clip = shared.copyOf()
        assertNull(
            ref.locate(
                episode,
                0L,
                clip,
                0L,
                100L,
                0L to 60_000L,
                targetDurationMs = 1_440_000L,
                referenceDurationMs = 1_471_000L,
            ),
        )
        assertEquals(
            10_000L to 70_000L,
            ref.locate(
                episode,
                0L,
                clip,
                0L,
                100L,
                0L to 60_000L,
                targetDurationMs = 1_440_000L,
                referenceDurationMs = 0L,
            ),
        )
    }

    @Test
    fun locateRejectsTooShortAMatch() {
        val random = Random(3)
        val episode = IntArray(3_000) { random.nextInt() }
        val clip = IntArray(700) { i -> if (i in 50 until 100) episode[700 + i] else random.nextInt() }
        assertNull(ref.locate(episode, 0L, clip, 35_000L, 100L, 40_000L to 100_000L))
    }
}
