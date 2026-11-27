package com.nuvio.app.features.analysis

import com.nuvio.app.features.downloads.PlaybackPriority
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisQueuePauseTest {
    @AfterTest
    fun clearOverride() {
        AnalysisWarmOverride.reset()
    }

    @Test
    fun everyQueuedItemUsesTheSameQueuePause() {
        val parts = AnalysisParts(
            intro = AnalysisPart.Pending,
            credits = AnalysisPart.Ready,
            recap = AnalysisPart.Off,
            subtitleSync = AnalysisPart.Ready,
            loudness = AnalysisPart.Ready,
            previews = AnalysisPart.Ready,
        )
        val bases = listOf(
            ItemAnalysisStatus.Queued,
            ItemAnalysisStatus.Queued,
            ItemAnalysisStatus.Done(parts, complete = false),
            ItemAnalysisStatus.Done(parts, complete = true),
            ItemAnalysisStatus.Running(AnalysisStage.Audio, overall = 0.2),
            ItemAnalysisStatus.Failed("stopped", parts = null),
        )
        val holds = listOf(
            AnalysisJobState.WaitingForHeat to AnalysisQueuePause.Heat,
            AnalysisJobState.WaitingForPlayback to AnalysisQueuePause.Playback,
            AnalysisJobState.WaitingWhileDownloading to AnalysisQueuePause.Warm,
            AnalysisJobState.WaitingForLowPower to AnalysisQueuePause.LowPower,
            AnalysisJobState.WaitingForCharger to AnalysisQueuePause.Charger,
            AnalysisJobState.Paused to AnalysisQueuePause.Other,
        )
        holds.forEach { (job, expected) ->
            val pause = analysisQueuePause(job, waitingForCharger = false)
            assertEquals(expected, pause, job.toString())
            val shown = bases.map { applyQueuePause(it, pause) }
            val paused = shown.filterIsInstance<ItemAnalysisStatus.Paused>()
            assertEquals(3, paused.size, job.toString())
            assertTrue(paused.all { it.pause == expected }, job.toString())
            assertTrue(shown[3] is ItemAnalysisStatus.Done)
            assertTrue((shown[3] as ItemAnalysisStatus.Done).complete)
            assertTrue(shown[4] is ItemAnalysisStatus.Running)
            assertTrue(shown[5] is ItemAnalysisStatus.Failed)
        }
        assertNull(analysisQueuePause(AnalysisJobState.Idle, waitingForCharger = false))
        assertEquals(
            AnalysisQueuePause.Charger,
            analysisQueuePause(AnalysisJobState.Idle, waitingForCharger = true),
        )
        assertNull(analysisQueuePause(AnalysisJobState.Starting, waitingForCharger = true))
    }

    @Test
    fun warmOverrideRunsAtFairAndRefusesSeriousOrCritical() {
        assertTrue(warmOverrideMayArm("download", AnalysisThermal.Fair))
        assertFalse(warmOverrideMayArm("thermal-serious", AnalysisThermal.Serious))
        assertFalse(warmOverrideMayArm("thermal-critical", AnalysisThermal.Critical))
        assertFalse(warmOverrideMayArm("low-power", AnalysisThermal.Fair))
        assertFalse(warmOverrideMayArm("playback", AnalysisThermal.Fair))
        assertFalse(warmOverrideMayArm("charger", AnalysisThermal.Fair))
        assertFalse(warmOverrideMayArm(null, AnalysisThermal.Nominal))

        AnalysisWarmOverride.arm()
        assertTrue(AnalysisWarmOverride.armed)
        assertNull(
            effectiveAnalysisPauseReason(
                videoPlaying = false,
                thermal = AnalysisThermal.Fair,
                lowPowerMode = false,
                transferActive = true,
                warmOverride = true,
            ),
        )
        assertEquals(
            0,
            analysisThrottleLevel(
                PlaybackPriority.Level.Normal,
                videoPlaying = false,
                thermal = AnalysisThermal.Fair,
                transferActive = true,
                warmOverride = true,
            ),
        )
        assertEquals(
            "thermal-serious",
            effectiveAnalysisPauseReason(
                videoPlaying = false,
                thermal = AnalysisThermal.Serious,
                lowPowerMode = false,
                transferActive = true,
                warmOverride = true,
            ),
        )
        assertEquals(
            "thermal-critical",
            effectiveAnalysisPauseReason(
                videoPlaying = false,
                thermal = AnalysisThermal.Critical,
                lowPowerMode = false,
                transferActive = false,
                warmOverride = true,
            ),
        )
        assertEquals(
            3,
            analysisThrottleLevel(
                PlaybackPriority.Level.Normal,
                videoPlaying = false,
                thermal = AnalysisThermal.Critical,
                warmOverride = true,
            ),
        )
        AnalysisWarmOverride.stopIfHot("thermal-serious")
        assertFalse(AnalysisWarmOverride.armed)
        AnalysisWarmOverride.arm()
        AnalysisWarmOverride.stopIfHot("thermal-critical")
        assertFalse(AnalysisWarmOverride.armed)
    }

    @Test
    fun warmOverrideResetsAfterTheRun() {
        AnalysisWarmOverride.arm()
        assertNull(
            effectiveAnalysisPauseReason(
                videoPlaying = false,
                thermal = AnalysisThermal.Fair,
                lowPowerMode = false,
                transferActive = true,
                warmOverride = AnalysisWarmOverride.armed,
            ),
        )
        AnalysisWarmOverride.reset()
        assertFalse(AnalysisWarmOverride.armed)
        assertEquals(
            "download",
            effectiveAnalysisPauseReason(
                videoPlaying = false,
                thermal = AnalysisThermal.Fair,
                lowPowerMode = false,
                transferActive = true,
                warmOverride = AnalysisWarmOverride.armed,
            ),
        )
        assertEquals(
            3,
            analysisThrottleLevel(
                PlaybackPriority.Level.Normal,
                videoPlaying = false,
                thermal = AnalysisThermal.Fair,
                transferActive = true,
                warmOverride = AnalysisWarmOverride.armed,
            ),
        )
    }
}
