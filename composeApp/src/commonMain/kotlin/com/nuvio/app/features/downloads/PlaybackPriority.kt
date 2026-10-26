package com.nuvio.app.features.downloads

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.analysis.setAnalysisThrottleLevel

/**
 * Playback first, then downloads, then on-device analysis. Throttles rather than stops:
 *
 * - [Level.Normal]: no player, or it has been paused for [PAUSED_RELEASE_MS]. Full speed.
 * - [Level.Playing]: one download at a time, analysis keeps going in small slices with sleeps.
 * - [Level.Strained]: the player stalled mid-playback (a local file read at ~150 kB/s with two
 *   downloads writing and the analyser decoding is how this was found): waiting for data for
 *   [STRAIN_AFTER_MS] at the spot it was playing. Downloads wait and analysis pauses until
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

    fun sessionStarted(isLocalFile: Boolean) {
        sessionOpen = true
        pausedSinceMs = 0L
        bufferingSinceMs = 0L
        lastStrainMs = 0L
        playedSinceLoad = false
        lastPositionMs = -1L
        lastTickMs = 0L
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
            if (level != Level.Normal && nowMs - pausedSinceMs >= PAUSED_RELEASE_MS) {
                InAppLogger.info("PlaybackPriority", "paused for a while, background work at full speed")
                apply(Level.Normal)
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
            Level.Playing -> Unit
        }
    }

    /** "Finish first": the user paused to let this episode's analysis run at full speed. */
    var analysisBoosted: Boolean = false
        private set

    fun boostAnalysis(on: Boolean) {
        analysisBoosted = on
        setAnalysisThrottleLevel(if (on) 0 else level.analysisThrottle)
    }

    fun sessionEnded() {
        analysisBoosted = false
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
        setAnalysisThrottleLevel(if (analysisBoosted) 0 else next.analysisThrottle)
        InAppLogger.info("PlaybackPriority", "level=$next downloads allowed=${downloadCap()}")
        reapplyDownloadCap()
        // Make sure queued analysis is running (throttled) rather than left waiting.
        if (next != Level.Strained) com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick()
    }
}
