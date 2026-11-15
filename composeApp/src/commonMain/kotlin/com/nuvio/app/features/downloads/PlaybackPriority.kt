package com.nuvio.app.features.downloads

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.analysis.AnalysisPower
import com.nuvio.app.features.analysis.analysisDiagnosticsLine
import com.nuvio.app.features.analysis.analysisPauseReason
import com.nuvio.app.features.analysis.analysisQosLabel
import com.nuvio.app.features.analysis.analysisThrottleLevel
import com.nuvio.app.features.analysis.setAnalysisPlayerSessionOpen
import com.nuvio.app.features.analysis.setAnalysisThrottleLevel
import com.nuvio.app.features.diagnostics.DiagnosticsLogger

/**
 * Playback first, then downloads, then on-device analysis.
 *
 * - [Level.Normal]: no player, or it has been paused for [PAUSED_RELEASE_MS]. Analysis may run,
 *   unless the phone is seriously hot or in Low Power Mode.
 * - [Level.Playing]: one download at a time. While the video is actually playing, intro, segment,
 *   and learning analysis is paused. A pause or the end of playback lets it resume.
 *   Downloads still use this level's cap until [PAUSED_RELEASE_MS].
 * - [Level.Strained]: the player stalled mid-playback (a local file read at ~150 kB/s with two
 *   downloads writing and the analyser decoding is how this was found): waiting for data for
 *   [STRAIN_AFTER_MS] at the spot it was playing. Downloads wait and analysis stays paused until
 *   [STRAIN_COOL_DOWN_MS] after the last such stall, then back to Playing.
 *
 * Loading that is not a stall never strains or extends the cool-down: the first load before a
 * frame has played, the load after a seek or a reload (the position jumps), and shorter blips.
 */
internal object PlaybackPriority {
    enum class Level(val maxDownloads: Int, val analysisThrottle: Int) {
        Normal(MAX_ACTIVE_DOWNLOADS, 0),
        Playing(1, 1),
        Strained(0, 2),
    }

    private const val PAUSED_RELEASE_MS = 2 * 60_000L
    private const val STRAIN_AFTER_MS = 1_500L
    private const val STRAIN_COOL_DOWN_MS = 30_000L
    /** A position change this far beyond what playback (up to 4x) explains is a seek or reload. */
    private const val JUMP_SLACK_MS = 3_000L
    private const val MAX_SPEED = 4L

    var level: Level = Level.Normal
        private set
    private var sessionOpen = false
    private var pausedSinceMs = 0L
    private var bufferingSinceMs = 0L
    private var lastStrainMs = 0L
    private var playedSinceLoad = false
    private var lastPositionMs = -1L
    private var lastTickMs = 0L
    /** Last level written to the platform, so a playing snapshot does not rewrite it every tick. */
    private var appliedAnalysisThrottle = 0
    private var appliedPauseReason: String? = null

    fun sessionStarted(isLocalFile: Boolean) {
        sessionOpen = true
        pausedSinceMs = 0L
        bufferingSinceMs = 0L
        lastStrainMs = 0L
        playedSinceLoad = false
        lastPositionMs = -1L
        lastTickMs = 0L
        setAnalysisPlayerSessionOpen(true)
        InAppLogger.info("PlaybackPriority", "player opened local=$isLocalFile")
        apply(Level.Playing, always = true)
    }

    /**
     * Every player snapshot (main thread). [wantsToPlay] is play-when-ready, [buffering] the
     * player waiting for data (mpv: idle, seeking or paused for cache), [positionMs] the playhead.
     */
    fun noteSnapshot(wantsToPlay: Boolean, buffering: Boolean, positionMs: Long, nowMs: Long) {
        if (!sessionOpen) return
        if (lastPositionMs >= 0L && lastTickMs > 0L) {
            val moved = positionMs - lastPositionMs
            if (moved < -JUMP_SLACK_MS || moved > (nowMs - lastTickMs) * MAX_SPEED + JUMP_SLACK_MS) {
                playedSinceLoad = false
                bufferingSinceMs = 0L
            }
        }
        lastPositionMs = positionMs
        lastTickMs = nowMs
        if (!wantsToPlay) {
            bufferingSinceMs = 0L
            if (pausedSinceMs == 0L) pausedSinceMs = nowMs
            // The video is not playing, so analysis may resume. Heat and Low Power Mode still hold it.
            // Download cap is unchanged until the pause has lasted [PAUSED_RELEASE_MS].
            publishAnalysisThrottle(videoPlaying = false)
            if (level != Level.Normal && nowMs - pausedSinceMs >= PAUSED_RELEASE_MS) {
                InAppLogger.info("PlaybackPriority", "paused for a while, background work at full speed")
                apply(Level.Normal)
            } else if (level == Level.Strained && nowMs - lastStrainMs >= STRAIN_COOL_DOWN_MS) {
                // Paused or at the end nothing stalls: the cool-down runs out here too.
                apply(Level.Playing)
            }
            return
        }
        pausedSinceMs = 0L
        if (buffering) {
            if (level == Level.Normal) apply(Level.Playing)
            if (!playedSinceLoad) return
            if (bufferingSinceMs == 0L) bufferingSinceMs = nowMs
            if (nowMs - bufferingSinceMs >= STRAIN_AFTER_MS) {
                lastStrainMs = nowMs
                if (level != Level.Strained) {
                    InAppLogger.info("PlaybackPriority", "player stalled, downloads and analysis wait")
                    apply(Level.Strained)
                }
            }
            return
        }
        bufferingSinceMs = 0L
        playedSinceLoad = true
        when (level) {
            Level.Normal -> apply(Level.Playing)
            Level.Strained -> if (nowMs - lastStrainMs >= STRAIN_COOL_DOWN_MS) apply(Level.Playing)
            Level.Playing -> publishAnalysisThrottle(videoPlaying = true)
        }
    }

    /** "Finish first": the user paused to let this episode's analysis run at full speed. */
    var analysisBoosted: Boolean = false
        private set

    fun boostAnalysis(on: Boolean) {
        analysisBoosted = on
        publishAnalysisThrottle(videoPlaying = sessionOpen && pausedSinceMs == 0L)
    }

    /** Thermal state or Low Power Mode changed. Logs every change, then reapplies the hold. */
    fun notePowerChanged() {
        val playing = sessionOpen && pausedSinceMs == 0L
        val power = AnalysisPower.snapshot()
        val line = analysisDiagnosticsLine(
            event = "thermal",
            job = com.nuvio.app.features.analysis.MediaAnalysisScheduler.activeJobId(),
            reason = "thermal-change",
            qos = analysisQosLabel(playerSessionOpen = sessionOpen, paused = playing || power.holdsAnalysis()),
            power = power,
        )
        InAppLogger.info("PlaybackPriority", line)
        DiagnosticsLogger.logPerformance(line)
        publishAnalysisThrottle(videoPlaying = playing)
    }

    /** Null while analysis may run. Otherwise playback, strain, heat, or Low Power Mode. */
    fun analysisPauseReason(): String? = appliedPauseReason

    fun sessionEnded() {
        analysisBoosted = false
        setAnalysisPlayerSessionOpen(false)
        if (!sessionOpen) return
        sessionOpen = false
        InAppLogger.info("PlaybackPriority", "player closed")
        apply(Level.Normal)
        // Whatever happened during playback, the queue picks up again and the labels refresh.
        com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick(startDelayMs = 3_000L)
        com.nuvio.app.features.analysis.MediaAnalysisStatus.resultsChanged()
    }

    /** Downloads allowed now: the level's cap, or none while the player is open when that is chosen. */
    fun downloadCap(): Int =
        if (level != Level.Normal && SmartDownloadSettings.snapshot().pauseDuringPlayback) 0 else level.maxDownloads

    fun reapplyDownloadCap() = DownloadsRepository.setPlaybackDownloadCap(downloadCap())

    private fun apply(next: Level, always: Boolean = false) {
        if (level == next && !always) return
        level = next
        publishAnalysisThrottle(videoPlaying = sessionOpen && pausedSinceMs == 0L)
        InAppLogger.info("PlaybackPriority", "level=$next downloads allowed=${downloadCap()}")
        reapplyDownloadCap()
        // A playing or hot phone holds the queue. Anything else should pick the work back up.
        if (appliedPauseReason == null) com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick()
    }

    private fun publishAnalysisThrottle(videoPlaying: Boolean) {
        val power = AnalysisPower.snapshot()
        val next = analysisThrottleLevel(
            level,
            videoPlaying = videoPlaying,
            thermal = power.thermal,
            lowPowerMode = power.lowPowerMode,
        )
        val reason = when (next) {
            0 -> null
            1 -> "playback"
            2 -> "playback-strained"
            else -> analysisPauseReason(videoPlaying = false, power.thermal, power.lowPowerMode) ?: "power"
        }
        if (next == appliedAnalysisThrottle && reason == appliedPauseReason) return
        val previous = appliedPauseReason
        appliedAnalysisThrottle = next
        appliedPauseReason = reason
        setAnalysisThrottleLevel(next)
        val job = com.nuvio.app.features.analysis.MediaAnalysisScheduler.activeJobId()
        val qos = analysisQosLabel(playerSessionOpen = sessionOpen, paused = next != 0)
        val powerLine = { event: String, why: String ->
            analysisDiagnosticsLine(event, job, why, qos, power)
        }
        if (previous == null && reason != null) {
            logAnalysisHold(powerLine("stop", reason))
        } else if (previous != null && reason == null) {
            logAnalysisHold(powerLine("start", "clear"))
            com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick(startDelayMs = 0L)
        } else if (reason != null && previous != reason) {
            logAnalysisHold(powerLine("stop", reason))
        }
    }

    private fun logAnalysisHold(line: String) {
        InAppLogger.info("PlaybackPriority", line)
        DiagnosticsLogger.logPerformance(line)
    }

    /** Process start: drop a session flag or throttle left behind by a player in an earlier run. */
    fun clearStaleSession() {
        sessionOpen = false
        analysisBoosted = false
        pausedSinceMs = 0L
        level = Level.Normal
        appliedAnalysisThrottle = 0
        appliedPauseReason = null
        setAnalysisPlayerSessionOpen(false)
        setAnalysisThrottleLevel(0)
    }
}

private fun com.nuvio.app.features.analysis.AnalysisPowerSnapshot.holdsAnalysis(): Boolean =
    analysisPauseReason(videoPlaying = false, thermal, lowPowerMode) != null
