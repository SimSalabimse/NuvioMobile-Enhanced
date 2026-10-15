package com.nuvio.app.features.downloads

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.analysis.setAnalysisThrottleLevel

/**
 * Playback first, then downloads, then on-device analysis. Throttles rather than stops:
 *
 * - [Level.Normal]: no player, or it has been paused for [PAUSED_RELEASE_MS]. Full speed.
 * - [Level.Playing]: one download at a time, analysis keeps going in small slices with sleeps.
 * - [Level.Strained]: the player is buffering (a local file read at ~150 kB/s with two downloads
 *   writing and the analyser decoding is how this was found). Downloads wait and analysis
 *   pauses until playback has run smoothly for [STRAIN_COOL_DOWN_MS], then back to Playing.
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

    var level: Level = Level.Normal
        private set
    private var sessionOpen = false
    private var pausedSinceMs = 0L
    private var bufferingSinceMs = 0L
    private var smoothSinceMs = 0L

    fun sessionStarted(isLocalFile: Boolean) {
        sessionOpen = true
        pausedSinceMs = 0L
        bufferingSinceMs = 0L
        smoothSinceMs = 0L
        InAppLogger.info("PlaybackPriority", "player opened local=$isLocalFile")
        apply(Level.Playing)
    }

    /**
     * Every player snapshot (main thread). [wantsToPlay] is play-when-ready, [buffering] the
     * player waiting for data.
     */
    fun noteSnapshot(wantsToPlay: Boolean, buffering: Boolean, nowMs: Long) {
        if (!sessionOpen) return
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
            smoothSinceMs = 0L
            if (bufferingSinceMs == 0L) bufferingSinceMs = nowMs
            if (level != Level.Strained && nowMs - bufferingSinceMs >= STRAIN_AFTER_MS) {
                InAppLogger.info("PlaybackPriority", "player buffering, downloads and analysis wait")
                apply(Level.Strained)
            } else if (level == Level.Normal) {
                apply(Level.Playing)
            }
            return
        }
        bufferingSinceMs = 0L
        if (smoothSinceMs == 0L) smoothSinceMs = nowMs
        when (level) {
            Level.Normal -> apply(Level.Playing)
            Level.Strained -> if (nowMs - smoothSinceMs >= STRAIN_COOL_DOWN_MS) apply(Level.Playing)
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

    private fun apply(next: Level) {
        if (level == next) return
        level = next
        setAnalysisThrottleLevel(if (analysisBoosted) 0 else next.analysisThrottle)
        DownloadsRepository.setPlaybackDownloadCap(next.maxDownloads)
        // Make sure queued analysis is running (throttled) rather than left waiting.
        if (next != Level.Strained) com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick()
    }
}
