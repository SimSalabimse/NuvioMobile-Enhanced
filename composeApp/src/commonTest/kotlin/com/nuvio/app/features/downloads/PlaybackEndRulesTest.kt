package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEndRulesTest {
    private val episode = 43 * 60_000L

    @Test
    fun watchedThresholdIsNotTheEnd() {
        // 90 % watched (the watched-history threshold) is ~4 minutes before the end.
        assertFalse(PlaybackEndRules.reachedEnd((episode * 0.9).toLong(), episode, null))
        // Trakt marks watched at 80 %: about 9 minutes left.
        assertFalse(PlaybackEndRules.reachedEnd((episode * 0.8).toLong(), episode, null))
        assertFalse(PlaybackEndRules.reachedEnd(episode - 10 * 60_000L, episode, null))
    }

    @Test
    fun lastTwoMinutesIsTheEnd() {
        assertTrue(PlaybackEndRules.reachedEnd(episode - 119_000L, episode, null))
        assertTrue(PlaybackEndRules.reachedEnd(episode, episode, null))
    }

    @Test
    fun detectedCreditsStartCountsWhenLate() {
        val credits = episode - 3 * 60_000L
        assertTrue(PlaybackEndRules.reachedEnd(credits, episode, credits))
        assertFalse(PlaybackEndRules.reachedEnd(credits - 1_000L, episode, credits))
    }

    @Test
    fun earlyCreditsMatchIsIgnored() {
        // A "credits" match 10 minutes before the end is a false match, not the end.
        val falseCredits = episode - 10 * 60_000L
        assertEquals(null, PlaybackEndRules.usableCreditsStart(falseCredits, episode))
        assertFalse(PlaybackEndRules.reachedEnd(falseCredits, episode, falseCredits))
    }

    @Test
    fun unknownDurationNeverEnds() {
        assertFalse(PlaybackEndRules.reachedEnd(50 * 60_000L, 0L, null))
        assertFalse(PlaybackEndRules.nearEnd(50 * 60_000L, 0L, null))
    }

    @Test
    fun smartDownloadsWaitForTheLastMinutes() {
        assertFalse(PlaybackEndRules.nearEnd(0L, episode, null))
        assertFalse(PlaybackEndRules.nearEnd(episode / 2, episode, null))
        assertFalse(PlaybackEndRules.nearEnd(episode - 6 * 60_000L, episode, null))
        assertTrue(PlaybackEndRules.nearEnd(episode - 5 * 60_000L, episode, null))
        val credits = episode - 4 * 60_000L
        assertTrue(PlaybackEndRules.nearEnd(credits, episode, credits))
    }
}
