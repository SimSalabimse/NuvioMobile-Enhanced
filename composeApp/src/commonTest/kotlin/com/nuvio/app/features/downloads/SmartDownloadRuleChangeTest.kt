package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartDownloadRuleChangeTest {
    private fun episode(season: Int, episode: Int) =
        MetaVideo(id = "series:$season:$episode", title = "S${season}E$episode", season = season, episode = episode)

    private fun show(count: Int = 2) = SmartDownloadRuleInput(
        effectiveCount = count,
        watched = listOf(1 to 1),
        completed = setOf(1 to 1),
        videos = (1..6).map { episode(1, it) },
        freeBytes = 20_000_000_000L,
        bytesPerEpisode = 1_000_000_000L,
    )

    @Test
    fun detailsSaveQueuesEligibleEpisodesWhilePlaybackStillWaits() {
        val playing = NextEpisodeDownloadFacts(
            durationMs = 40 * 60_000L,
            positionMs = 0L,
            knownSizeBytes = 1_000_000L,
        )
        assertTrue(decideNextEpisodeDownload(playing) is NextEpisodeDownloadDecision.StartAt)
        assertEquals(
            NextEpisodeDownloadDecision.StartNow,
            decideNextEpisodeDownload(playing.copy(positionMs = playing.durationMs)),
        )

        val decision = evaluateSmartDownloadRuleChange(show())
        assertTrue(decision.startsNow)
        assertTrue(decision.keepPending)
        assertEquals("Queued 2 episodes", decision.message)
        assertEquals("Queued 0 episodes", smartDownloadRuleQueued(0))
        assertEquals("Queued 0 episodes", smartDownloadRuleQueued(-2))
        assertEquals("Queued 1 episode", smartDownloadRuleQueued(1))
        assertEquals("Looking for the next 2 episodes", smartDownloadRuleSearching(2))
        val feedback = smartDownloadSettingsFeedback(
            listOf(
                "show-a" to decision,
                "show-b" to evaluateSmartDownloadRuleChange(show().copy(onWifi = false)),
            ),
        )
        assertEquals("Queued 2 episodes", feedback.message)
        assertEquals(listOf("show-a"), feedback.showsToStart)
        assertEquals(
            "Queued 0 episodes",
            smartDownloadSettingsFeedback(listOf("show-b" to evaluateSmartDownloadRuleChange(show().copy(onWifi = false)))).message,
        )
        assertEquals(listOf("S1E2", "S1E3"), decision.episodeCodes)
        assertEquals(1, decision.anchorSeason)
        assertEquals(1, decision.anchorEpisode)
        assertFalse(decision.cancelsDownloads)
        assertEquals(2, effectiveSmartDownloadCount(SeriesSmartDownloadCount.Two.count, globalCount = 1))
    }

    @Test
    fun wifiAndChargingBlockTheStartAndKeepTheRequest() {
        val wifi = evaluateSmartDownloadRuleChange(show().copy(onWifi = false))
        assertFalse(wifi.startsNow)
        assertTrue(wifi.keepPending)
        assertEquals("Waiting for Wi-Fi (Settings > Downloads > Only on Wi-Fi)", wifi.message)
        assertEquals(listOf("S1E2", "S1E3"), wifi.episodeCodes)
        assertFalse(wifi.cancelsDownloads)

        val charging = evaluateSmartDownloadRuleChange(
            show().copy(onlyWhileCharging = true, charging = false, analyserSupported = true),
        )
        assertFalse(charging.startsNow)
        assertTrue(charging.keepPending)
        assertEquals("Waiting for charging", charging.message)

        val noAnalyser = evaluateSmartDownloadRuleChange(
            show().copy(onlyWhileCharging = true, charging = false, analyserSupported = false),
        )
        assertTrue(noAnalyser.startsNow)
        assertEquals("Queued 2 episodes", noAnalyser.message)
    }

    @Test
    fun lowSpaceDoesNotQueueAndDoesNotCancel() {
        val decision = evaluateSmartDownloadRuleChange(
            show().copy(freeBytes = 500_000_000L, bytesPerEpisode = SMART_DOWNLOAD_MIN_FREE_BYTES),
        )
        assertFalse(decision.startsNow)
        assertTrue(decision.keepPending)
        assertEquals(SMART_DOWNLOADS_NO_SPACE, decision.message)
        assertTrue(decision.episodeCodes.isEmpty())
        assertFalse(decision.cancelsDownloads)
    }

    @Test
    fun noEligibleEpisodesSaysWhy() {
        val last = evaluateSmartDownloadRuleChange(show().copy(videos = listOf(episode(1, 1))))
        assertFalse(last.startsNow)
        assertFalse(last.keepPending)
        assertEquals(SMART_DOWNLOAD_RULE_NONE, last.message)

        val nothingSaved = evaluateSmartDownloadRuleChange(show().copy(hasShowDownload = false))
        assertEquals(SMART_DOWNLOAD_RULE_NEEDS_DOWNLOAD, nothingSaved.message)
        assertFalse(nothingSaved.startsNow)

        val notFinished = evaluateSmartDownloadRuleChange(
            show().copy(watched = emptyList(), completed = emptySet(), inProgress = emptySet()),
        )
        assertEquals(SMART_DOWNLOAD_RULE_NEEDS_FINISH, notFinished.message)

        val afterTheDownload = evaluateSmartDownloadRuleChange(
            show().copy(watched = emptyList(), completed = setOf(1 to 2)),
        )
        assertEquals(listOf("S1E3", "S1E4"), afterTheDownload.episodeCodes)

        val unaired = evaluateSmartDownloadRuleChange(show().copy(unaired = setOf(1 to 2, 1 to 3)))
        assertEquals(SMART_DOWNLOAD_RULE_UNAIRED, unaired.message)
        assertTrue(unaired.keepPending)
        assertFalse(unaired.startsNow)
        val dropped = evaluateSmartDownloadRuleChange(
            show().copy(unaired = setOf(1 to 2, 1 to 3), queueUnaired = false),
        )
        assertFalse(dropped.keepPending)
    }

    @Test
    fun repeatedChangeDoesNotQueueEpisodesAlreadyHeld() {
        val again = evaluateSmartDownloadRuleChange(show().copy(inProgress = setOf(1 to 2, 1 to 3)))
        assertFalse(again.startsNow)
        assertFalse(again.keepPending)
        assertEquals("The next episodes are already queued", again.message)

        val raised = evaluateSmartDownloadRuleChange(
            show(count = 3).copy(inProgress = setOf(1 to 2, 1 to 3)),
        )
        assertTrue(raised.startsNow)
        assertEquals("Queued 1 episode", raised.message)
        assertEquals(listOf("S1E4"), raised.episodeCodes)

        assertTrue(
            smartDownloadRequestReplaces(
                showId = "show", exact = false, count = null, season = 1, episode = 1,
                otherShowId = "show", otherExact = false, otherCount = null, otherSeason = 1, otherEpisode = 4,
            ),
        )
        assertFalse(
            smartDownloadRequestReplaces(
                showId = "show", exact = false, count = null, season = 1, episode = 1,
                otherShowId = "show", otherExact = true, otherCount = null, otherSeason = 1, otherEpisode = 2,
            ),
        )
        assertFalse(
            smartDownloadRequestReplaces(
                showId = "show", exact = false, count = null, season = 1, episode = 1,
                otherShowId = "show", otherExact = false, otherCount = 3, otherSeason = 1, otherEpisode = 1,
            ),
        )
        assertFalse(
            smartDownloadRequestReplaces(
                showId = "show", exact = false, count = null, season = 1, episode = 1,
                otherShowId = "other", otherExact = false, otherCount = null, otherSeason = 1, otherEpisode = 1,
            ),
        )
    }

    @Test
    fun disablingDropsTheNextEpisodeRequestAndLeavesAManualDownloadAlone() {
        val off = evaluateSmartDownloadRuleChange(show().copy(effectiveCount = 0))
        assertTrue(off.dropPlainPending)
        assertFalse(off.startsNow)
        assertFalse(off.keepPending)
        assertFalse(off.cancelsDownloads)
        assertEquals(SMART_DOWNLOAD_RULE_COUNT_OFF, off.message)

        val global = evaluateSmartDownloadRuleChange(show().copy(globalEnabled = false))
        assertEquals(SMART_DOWNLOAD_RULE_OFF, global.message)
        assertFalse(global.dropPlainPending)
        assertFalse(global.startsNow)
        assertFalse(global.cancelsDownloads)

        val manual = evaluateSmartDownloadRuleChange(show().copy(completed = setOf(1 to 1, 1 to 5)))
        assertEquals(listOf("S1E2", "S1E3"), manual.episodeCodes)
        assertFalse(manual.episodeCodes.contains("S1E5"))
        assertFalse(manual.cancelsDownloads)
    }

    @Test
    fun privateFixtureAndAutomationDoNotStart() {
        val privateViewing = evaluateSmartDownloadRuleChange(show().copy(privateViewing = true))
        assertEquals(SMART_DOWNLOAD_RULE_PRIVATE, privateViewing.message)
        assertFalse(privateViewing.startsNow)
        assertFalse(privateViewing.keepPending)
        assertFalse(privateViewing.dropPlainPending)

        assertFalse(smartDownloadDetailsSaveProceeds(fixtureActive = true, automationActive = false, fixtureGoalHeld = false))
        assertFalse(smartDownloadDetailsSaveProceeds(fixtureActive = false, automationActive = true, fixtureGoalHeld = false))
        assertFalse(smartDownloadDetailsSaveProceeds(fixtureActive = false, automationActive = false, fixtureGoalHeld = true))
        assertTrue(smartDownloadDetailsSaveProceeds(fixtureActive = false, automationActive = false, fixtureGoalHeld = false))
    }
}
