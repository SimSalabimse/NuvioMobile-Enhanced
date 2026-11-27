package com.nuvio.app.features.downloads

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.analysis.AnalysisPower
import com.nuvio.app.features.analysis.MediaAnalyzerPlatform
import com.nuvio.app.features.analysis.analysisDiagnosticsLine
import com.nuvio.app.features.analysis.analysisPauseReason
import com.nuvio.app.features.analysis.analysisQosLabel
import com.nuvio.app.features.analysis.analysisThrottleLevel
import com.nuvio.app.features.analysis.sameAnalysisStop
import com.nuvio.app.features.analysis.logProcessMemory
import com.nuvio.app.features.analysis.setAnalysisPlayerSessionOpen
import com.nuvio.app.features.analysis.setAnalysisThrottleLevel
import com.nuvio.app.features.analysis.setAnalysisTransferActive
import com.nuvio.app.features.diagnostics.BehaviourEvents
import com.nuvio.app.features.diagnostics.BehaviourLogFiles
import com.nuvio.app.features.diagnostics.DiagnosticsLogger
import com.nuvio.app.features.diagnostics.behaviourContentId
import com.nuvio.app.features.diagnostics.behaviourPositionBucket

/**
 * Playback first, then downloads, then on-device analysis.
 *
 * - [Level.Normal]: no player, or it has been paused for [PAUSED_RELEASE_MS]. Analysis may run,
 *   unless the phone is seriously hot or in Low Power Mode.
 * - [Level.Playing]: one download at a time, only while the picture is actually moving (or the
 *   player has stalled after a frame already played). Opening or restoring the player does not
 *   count. A pause, the end, a dismiss, or leaving the app releases downloads at once. Intro,
 *   segment, and learning analysis follow the same picture. The level itself returns to Normal
 *   after [PAUSED_RELEASE_MS].
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
    private const val STALE_PLAYBACK_MS = 3_000L

    var level: Level = Level.Normal
        private set
    private var sessionOpen = false
    /**
     * Download lane. True only while the picture is moving, or the player has stalled after a
     * frame already played. Play-when-ready alone, including a restored player that is not
     * playing, does not hold downloads.
     */
    private var videoPlaying = false
    /** Analysis. True only while the picture is actually moving. */
    private var actuallyPlaying = false
    private var pictureInPicture = false
    private var backgroundSuspended = false
    private var lastLiveMs = 0L
    private var appliedDownloadGateKey: String? = null
    private var nextUpParentId: String? = null
    private var nextUpSeason: Int? = null
    private var nextUpEpisode: Int? = null
    private var behindDeadline = false
    private var lastDeadlineCheckMs = 0L
    private var lastRemainingMs = -1L
    private var dutyAnchorMs = 0L
    private var pausedSinceMs = 0L
    private var bufferingSinceMs = 0L
    private var lastStrainMs = 0L
    private var playedSinceLoad = false
    private var lastPositionMs = -1L
    private var lastTickMs = 0L
    /** Last level written to the platform, so a playing snapshot does not rewrite it every tick. */
    private var appliedAnalysisThrottle = 0
    private var appliedPauseReason: String? = null
    private var playbackPlainId: String? = null
    private var loggedPlayState = "idle"
    private var lastPowerKey: String? = null
    private var lastThermalLine: String? = null
    private var lastAnalysisStopLine: String? = null
    private var transfersActive = false
    private var nextUpBytesLeft = -1L
    private var nextUpBytesPerSecond = 0L
    private var nextUpRemainingMs = -1L

    fun sessionStarted(isLocalFile: Boolean) {
        sessionOpen = true
        pausedSinceMs = 0L
        bufferingSinceMs = 0L
        lastStrainMs = 0L
        playedSinceLoad = false
        lastPositionMs = -1L
        lastTickMs = 0L
        videoPlaying = false
        actuallyPlaying = false
        backgroundSuspended = false
        lastLiveMs = 0L
        appliedDownloadGateKey = null
        setAnalysisPlayerSessionOpen(true)
        logPresence("present")
        InAppLogger.info("PlaybackPriority", "player opened local=$isLocalFile")
        // Attached is not watching. A restored or still-loading player must not hold the queue.
        if (level != Level.Normal) apply(Level.Normal) else publishDownloadGate()
    }

    /**
     * Every player snapshot (main thread). [wantsToPlay] is play-when-ready. The download lane
     * and analysis follow [pictureMoving], plus a stall after a frame has already played.
     */
    fun noteSnapshot(
        wantsToPlay: Boolean,
        buffering: Boolean,
        positionMs: Long,
        nowMs: Long,
        pictureMoving: Boolean = wantsToPlay,
        ended: Boolean = false,
        failed: Boolean = false,
    ) {
        lastLiveMs = nowMs
        if (backgroundSuspended) return
        if (!sessionOpen) {
            sessionOpen = true
            setAnalysisPlayerSessionOpen(true)
            logPresence("present")
        }
        val moving = pictureMoving && !ended && !failed
        val intending = wantsToPlay && !ended && !failed
        // A stall only counts after the picture has moved. The first load, a seek, and a
        // restored play-when-ready flag have not.
        val stalled = intending && buffering && playedSinceLoad
        if (ended || failed) {
            val reason = if (ended) "end" else "error"
            val wasOn = actuallyPlaying || videoPlaying
            actuallyPlaying = false
            videoPlaying = false
            if (wasOn) logPresence(reason)
        } else {
            if (actuallyPlaying != moving) {
                actuallyPlaying = moving
                logPresence(if (moving) "playing" else "paused")
            }
            videoPlaying = moving || stalled
        }
        try {
            noteSnapshotBody(buffering, positionMs, nowMs)
        } finally {
            logPlayback(if (videoPlaying) "start" else "pause", positionMs)
            publishDownloadGate()
            publishAnalysisThrottle(videoPlaying = actuallyPlaying && sessionOpen)
        }
    }

    private fun noteSnapshotBody(buffering: Boolean, positionMs: Long, nowMs: Long) {
        if (lastPositionMs >= 0L && lastTickMs > 0L) {
            val moved = positionMs - lastPositionMs
            if (moved < -JUMP_SLACK_MS || moved > (nowMs - lastTickMs) * MAX_SPEED + JUMP_SLACK_MS) {
                playedSinceLoad = false
                bufferingSinceMs = 0L
            }
        }
        lastPositionMs = positionMs
        lastTickMs = nowMs
        if (!videoPlaying) {
            bufferingSinceMs = 0L
            if (pausedSinceMs == 0L) pausedSinceMs = nowMs
            // The video is not playing, so analysis may resume. Heat and Low Power Mode still hold it.
            // Downloads resume as soon as playback pauses. The two-minute wait is only the
            // analysis level returning to Normal.
            publishAnalysisThrottle(videoPlaying = false)
            com.nuvio.app.features.analysis.NextUpAnalysis.onPaused()
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
            Level.Playing -> publishAnalysisThrottle(videoPlaying = actuallyPlaying)
        }
    }

    /** "Finish first": the user paused to let this episode's analysis run at full speed. */
    var analysisBoosted: Boolean = false
        private set

    fun boostAnalysis(on: Boolean) {
        analysisBoosted = on
        publishAnalysisThrottle(videoPlaying = analysisPictureMoving(com.nuvio.app.features.streams.epochMs()))
    }

    /** Thermal state or Low Power Mode changed. Logs every change, then reapplies the hold. */
    fun notePlaybackContent(plainId: String) {
        val trimmed = plainId.trim()
        playbackPlainId = trimmed.ifEmpty { null }
    }

    fun notePlaybackError(positionMs: Long) {
        logPlayback("error", positionMs)
        if (actuallyPlaying || videoPlaying) logPresence("error")
        actuallyPlaying = false
        videoPlaying = false
        publishAnalysisThrottle(videoPlaying = false)
        publishDownloadGate()
    }

    /** Nothing is playing in the background unless Picture in Picture is keeping it. */
    fun noteAppBackground(inPictureInPicture: Boolean) {
        pictureInPicture = inPictureInPicture
        if (inPictureInPicture) {
            logPresence("background-pip")
            return
        }
        if (!sessionOpen && !actuallyPlaying && !videoPlaying) return
        backgroundSuspended = true
        actuallyPlaying = false
        videoPlaying = false
        sessionOpen = false
        setAnalysisPlayerSessionOpen(false)
        logPresence("background")
        apply(Level.Normal)
    }

    fun noteAppForeground() {
        if (!backgroundSuspended) return
        backgroundSuspended = false
        logPresence("foreground")
    }

    fun notePictureInPictureStopped() {
        pictureInPicture = false
        if (actuallyPlaying || videoPlaying) {
            actuallyPlaying = false
            videoPlaying = false
            publishAnalysisThrottle(videoPlaying = false)
            publishDownloadGate()
        }
        logPresence("pip-stop")
    }

    fun notePlaybackEnded() {
        if (actuallyPlaying || videoPlaying) logPresence("end")
        actuallyPlaying = false
        videoPlaying = false
        publishAnalysisThrottle(videoPlaying = false)
        publishDownloadGate()
    }

    fun notePowerChanged() {
        val playing = analysisPictureMoving(com.nuvio.app.features.streams.epochMs())
        val power = AnalysisPower.snapshot()
        val powerKey = "${power.thermal.name.lowercase()}|${power.lowPowerMode}"
        if (powerKey != lastPowerKey) {
            lastPowerKey = powerKey
            BehaviourEvents.record(
                "power",
                mapOf(
                    "thermal" to power.thermal.name.lowercase(),
                    "lowPower" to if (power.lowPowerMode) "true" else "false",
                ),
            )
        }
        val line = analysisDiagnosticsLine(
            event = "thermal",
            job = com.nuvio.app.features.analysis.MediaAnalysisScheduler.activeJobId(),
            reason = "thermal-change",
            qos = analysisQosLabel(
                playerSessionOpen = sessionOpen,
                paused = playing || power.holdsAnalysis(),
                transferActive = transfersActive,
            ),
            power = power,
        )
        if (line != lastThermalLine) {
            lastThermalLine = line
            InAppLogger.info("PlaybackPriority", line)
            DiagnosticsLogger.logPerformance(line)
        }
        publishAnalysisThrottle(videoPlaying = playing)
        publishDownloadGate()
    }

    /**
     * Live heat, read again before a start. A stored throttle of 0 is not enough:
     * the power path checks this, including after a hold whose reason was "power".
     */
    fun refreshAnalysisHold() {
        publishAnalysisThrottle(videoPlaying = analysisPictureMoving(com.nuvio.app.features.streams.epochMs()))
    }

    fun pauseReasonNow(): String? {
        val playing = analysisPictureMoving(com.nuvio.app.features.streams.epochMs())
        publishAnalysisThrottle(videoPlaying = playing)
        val power = AnalysisPower.snapshot()
        val reason = com.nuvio.app.features.analysis.effectiveAnalysisPauseReason(
            playing,
            power.thermal,
            power.lowPowerMode,
            transfersActive,
            warmOverride = com.nuvio.app.features.analysis.AnalysisWarmOverride.armed,
        )
        if (reason != null && reason != appliedPauseReason) publishAnalysisThrottle(videoPlaying = playing)
        return reason
    }

    /** Null while analysis may run. Otherwise playback, strain, heat, or Low Power Mode. */
    fun analysisPauseReason(): String? = appliedPauseReason

    fun sessionEnded() {
        val hadSession = sessionOpen || videoPlaying || actuallyPlaying
        clearNextUp()
        analysisBoosted = false
        backgroundSuspended = false
        pictureInPicture = false
        if (hadSession) logPresence("dismiss")
        actuallyPlaying = false
        videoPlaying = false
        appliedDownloadGateKey = null
        setAnalysisPlayerSessionOpen(false)
        if (!sessionOpen && !hadSession) {
            reapplyDownloadCap()
            return
        }
        sessionOpen = false
        logPlayback("stop", lastPositionMs)
        loggedPlayState = "idle"
        InAppLogger.info("PlaybackPriority", "player closed")
        apply(Level.Normal)
        // Whatever happened during playback, the queue picks up again and the labels refresh.
        com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick(startDelayMs = 3_000L)
        com.nuvio.app.features.analysis.MediaAnalysisStatus.resultsChanged()
    }

    /**
     * Downloads follow the same heat, Low Power Mode, and playback signal as analysis.
     * While a video is playing the queue keeps one transfer and starts no Smart Download.
     * A pause or stop opens the queue again. Wi-Fi plus charging at nominal heat runs at full speed.
     */
    /** The episode after the one on screen. Only that file may transfer while playback continues. */
    fun noteNextUp(parentMetaId: String, season: Int, episode: Int) {
        if (parentMetaId == nextUpParentId && season == nextUpSeason && episode == nextUpEpisode) return
        nextUpParentId = parentMetaId
        nextUpSeason = season
        nextUpEpisode = episode
        behindDeadline = false
        lastDeadlineCheckMs = 0L
        lastRemainingMs = -1L
        appliedDownloadGateKey = null
        if (sessionOpen) publishDownloadGate()
    }

    fun isNextUp(item: DownloadItem): Boolean =
        item.parentMetaId == nextUpParentId &&
            item.seasonNumber == nextUpSeason &&
            item.episodeNumber == nextUpEpisode

    /**
     * Once a minute, compare the measured rate with the time left in the episode.
     * A late file is raised to normal priority unless the phone is seriously hot.
     */
    fun noteNextUpProgress(bytesLeft: Long, bytesPerSecond: Long, episodeRemainingMs: Long, nowMs: Long) {
        nextUpBytesLeft = bytesLeft
        nextUpBytesPerSecond = bytesPerSecond
        nextUpRemainingMs = episodeRemainingMs
        if (!deadlineRecheckDue(nowMs, lastDeadlineCheckMs, episodeRemainingMs, lastRemainingMs)) return
        lastDeadlineCheckMs = nowMs
        lastRemainingMs = episodeRemainingMs
        val late = nextUpBehindDeadline(bytesLeft, bytesPerSecond, episodeRemainingMs)
        if (late == behindDeadline) return
        behindDeadline = late
        appliedDownloadGateKey = null
        publishDownloadGate()
    }

    fun downloadGate(): DownloadGateDecision {
        val playing = watchingNow()
        val power = AnalysisPower.snapshot()
        val gate = playbackDownloadGate(
            videoPlaying = playing,
            thermal = power.thermal,
            lowPowerMode = power.lowPowerMode,
            charging = MediaAnalyzerPlatform.isCharging(),
            onWifi = DownloadNetworkGuard.isOnWifi(),
            behindDeadline = behindDeadline,
            pauseWhileWatching = SmartDownloadSettings.snapshot().pauseDuringPlayback,
        )
        if (level != Level.Strained || !playing) return gate
        val held = gate.nextUp.copy(
            state = DownloadGateState.Hold,
            pace = TransferPace.Stopped,
            allowStart = false,
            reason = "stall",
        )
        return gate.copy(state = DownloadGateState.Hold, reason = "stall", maxActive = 0, nextUp = held, lowPriority = false)
    }

    fun downloadCap(): Int = downloadGate().maxActive

    /** Serious or critical heat, and nothing playing: the background session transfers nothing. */
    fun backgroundSessionHold(): Boolean {
        val power = AnalysisPower.snapshot()
        return backgroundSessionHolds(watchingNow(), power.thermal)
    }

    /**
     * True only while this process has a snapshot of the picture moving, or of a stall after a
     * frame already played. A player that is merely open, restored, or paused is not watching.
     * With no clock, that is the last snapshot's own flag. Pass [nowMs] from the same clock as
     * the snapshot to treat one older than [STALE_PLAYBACK_MS] as not watching. Picture in
     * Picture keeps the last snapshot.
     */
    fun isWatching(nowMs: Long = -1L): Boolean {
        if (backgroundSuspended || !sessionOpen || !videoPlaying) return false
        if (nowMs >= 0L && snapshotIsStale(nowMs)) return false
        return true
    }

    /** Drops a watch hold whose last snapshot has gone stale, and reopens the download cap. */
    fun releaseStaleWatchHold(nowMs: Long = com.nuvio.app.features.streams.epochMs()) {
        dropStaleWatchHold(nowMs)
    }

    private fun watchingNow(): Boolean = isWatching()

    /**
     * A clock that is behind [lastLiveMs] is a different time base (tests), not a stale player.
     * Picture in Picture keeps the last snapshot.
     */
    private fun snapshotIsStale(nowMs: Long): Boolean {
        if (pictureInPicture || lastLiveMs <= 0L || nowMs < lastLiveMs) return false
        return nowMs - lastLiveMs > STALE_PLAYBACK_MS
    }

    fun reapplyDownloadCap() = DownloadsRepository.setPlaybackDownloadCap(downloadCap())

    /** A transfer starting or finishing. Analysis holds from fair heat upward while one is active. */
    fun noteTransfersChanged(active: Boolean) {
        val changed = active != transfersActive
        transfersActive = active
        setAnalysisTransferActive(active)
        if (!changed) return
        publishAnalysisThrottle(videoPlaying = analysisPictureMoving(com.nuvio.app.features.streams.epochMs()))
    }

    private fun publishDownloadGate() {
        val gate = downloadGate()
        val power = AnalysisPower.snapshot()
        val key = "${gate.state}|${gate.reason}|${gate.maxActive}|${gate.nextUp.pace}|${gate.other.pace}|${gate.other.allowStart}|${gate.dutyCycle}|${power.thermal.name}|${power.lowPowerMode}"
        val phase = dutyPhase()
        val published = "$key|$phase"
        if (published == appliedDownloadGateKey) return
        appliedDownloadGateKey = published
        com.nuvio.app.features.diagnostics.BehaviourEvents.record(
            "download-gate",
            mapOf(
                "state" to gate.state.name.lowercase(),
                "reason" to gate.reason,
                "phase" to phase,
                "thermal" to power.thermal.name.lowercase(),
                "lowPower" to if (power.lowPowerMode) "true" else "false",
                "cap" to gate.maxActive.toString(),
            ),
        )
        reapplyDownloadCap()
    }

    fun dutyWindowOpen(nowMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): Boolean {
        val gate = downloadGate()
        if (!gate.dutyCycle) return true
        if (dutyAnchorMs == 0L) dutyAnchorMs = nowMs
        return dutyCycleTransferring(nowMs - dutyAnchorMs)
    }

    private fun dutyPhase(): String {
        if (!downloadGate().dutyCycle) return "on"
        return if (dutyWindowOpen()) "on" else "off"
    }

    fun nextUpEtaSeconds(): Long = com.nuvio.app.features.downloads.nextUpEtaSeconds(nextUpBytesLeft, nextUpBytesPerSecond)

    fun nextUpRemainingSeconds(): Long = if (nextUpRemainingMs < 0L) -1L else nextUpRemainingMs / 1000L

    fun nextUpBytesPerSecond(): Long = nextUpBytesPerSecond

    private fun clearNextUp() {
        nextUpParentId = null
        nextUpSeason = null
        nextUpEpisode = null
        behindDeadline = false
        lastDeadlineCheckMs = 0L
        lastRemainingMs = -1L
        dutyAnchorMs = 0L
        nextUpBytesLeft = -1L
        nextUpBytesPerSecond = 0L
        nextUpRemainingMs = -1L
    }

    private fun logPlayback(state: String, positionMs: Long) {
        if (state == loggedPlayState) return
        loggedPlayState = state
        val fields = linkedMapOf(
            "state" to state,
            "pos" to behaviourPositionBucket(positionMs).toString(),
        )
        val plain = playbackPlainId
        if (!plain.isNullOrBlank()) {
            fields["id"] = behaviourContentId(BehaviourLogFiles.installSalt(), plain)
        }
        BehaviourEvents.record("playback", fields)
    }

    fun nextUpPlainId(): String? {
        val parent = nextUpParentId ?: return null
        val season = nextUpSeason ?: return null
        val episode = nextUpEpisode ?: return null
        return "$parent:$season:$episode"
    }

    private fun apply(next: Level, always: Boolean = false) {
        if (level == next && !always) return
        level = next
        publishAnalysisThrottle(videoPlaying = actuallyPlaying && sessionOpen && !backgroundSuspended)
        InAppLogger.info("PlaybackPriority", "level=$next downloads allowed=${downloadCap()}")
        publishDownloadGate()
        // Heat is read again before a start, including the power path.
        if (pauseReasonNow() == null) com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick()
    }

    private fun publishAnalysisThrottle(videoPlaying: Boolean) {
        val power = AnalysisPower.snapshot()
        val next = analysisThrottleLevel(
            level,
            videoPlaying = videoPlaying,
            thermal = power.thermal,
            lowPowerMode = power.lowPowerMode,
            transferActive = transfersActive,
            warmOverride = com.nuvio.app.features.analysis.AnalysisWarmOverride.armed,
        )
        val reason = when (next) {
            0 -> null
            1 -> "playback"
            2 -> "playback-strained"
            else -> com.nuvio.app.features.analysis.effectiveAnalysisPauseReason(
                videoPlaying = false,
                power.thermal,
                power.lowPowerMode,
                transfersActive,
                warmOverride = com.nuvio.app.features.analysis.AnalysisWarmOverride.armed,
            ) ?: "power"
        }
        if (next == appliedAnalysisThrottle && reason == appliedPauseReason) return
        val previous = appliedPauseReason
        appliedAnalysisThrottle = next
        appliedPauseReason = reason
        setAnalysisThrottleLevel(next)
        val job = com.nuvio.app.features.analysis.MediaAnalysisScheduler.activeJobId()
        val qos = analysisQosLabel(
            playerSessionOpen = sessionOpen,
            paused = next != 0,
            transferActive = transfersActive,
        )
        val powerLine = { event: String, why: String ->
            analysisDiagnosticsLine(event, job, why, qos, power)
        }
        if (previous == null && reason != null) {
            logAnalysisHold(powerLine("stop", reason))
            recordAnalysis("skip", reason, "full")
        } else if (previous != null && reason == null) {
            logAnalysisHold(powerLine("start", "clear"))
            recordAnalysis("start", "clear", "full")
            com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick(startDelayMs = 0L)
        } else if (reason != null && previous != reason) {
            logAnalysisHold(powerLine("stop", reason))
            recordAnalysis("skip", reason, "full")
        }
    }

    private fun recordAnalysis(action: String, reason: String, window: String) {
        BehaviourEvents.record(
            "analysis",
            mapOf("action" to action, "reason" to reason, "window" to window),
        )
    }

    private fun logAnalysisHold(line: String) {
        if (sameAnalysisStop(lastAnalysisStopLine, line)) return
        if (line.startsWith("analysis stop")) lastAnalysisStopLine = line
        if (line.startsWith("analysis start")) lastAnalysisStopLine = null
        InAppLogger.info("PlaybackPriority", line)
        DiagnosticsLogger.logPerformance(line)
        val event = when {
            line.startsWith("analysis stop") -> "analysis-stop"
            line.startsWith("analysis start") -> "analysis-start"
            else -> "analysis"
        }
        logProcessMemory(event)
    }

    /**
     * A snapshot that stopped arriving is not playback. Downloads and analysis both let go.
     * Picture in Picture keeps the last snapshot: that player is still on screen.
     */
    private var droppingStale = false

    private fun dropStaleWatchHold(nowMs: Long) {
        if (droppingStale || pictureInPicture || backgroundSuspended || (!videoPlaying && !actuallyPlaying)) return
        if (lastLiveMs <= 0L || nowMs - lastLiveMs <= STALE_PLAYBACK_MS) return
        droppingStale = true
        try {
            actuallyPlaying = false
            videoPlaying = false
            logPresence("stale")
            publishAnalysisThrottle(videoPlaying = false)
            publishDownloadGate()
        } finally {
            droppingStale = false
        }
    }

    /** Analyse now. A quiet picture does not keep the playback hold. The download lane stays. */
    fun dropStalePlaybackForManualStart() {
        val nowMs = com.nuvio.app.features.streams.epochMs()
        if (analysisPictureMoving(nowMs) || !actuallyPlaying) return
        actuallyPlaying = false
        logPresence("stale")
        publishAnalysisThrottle(videoPlaying = false)
    }

    /**
     * True only while a snapshot says the picture is moving and that snapshot is still fresh.
     */
    internal fun analysisPictureMoving(nowMs: Long): Boolean {
        if (backgroundSuspended || !sessionOpen || !actuallyPlaying) return false
        if (!pictureInPicture && lastLiveMs > 0L && nowMs - lastLiveMs > STALE_PLAYBACK_MS) return false
        return true
    }

    private fun logPresence(reason: String) {
        BehaviourEvents.record(
            "playback-presence",
            mapOf(
                "playing" to if (actuallyPlaying) "true" else "false",
                "attached" to if (sessionOpen) "true" else "false",
                "reason" to reason,
            ),
        )
    }

    /** Process start: drop a session flag or throttle left behind by a player in an earlier run. */
    fun clearStaleSession() {
        sessionOpen = false
        videoPlaying = false
        actuallyPlaying = false
        pictureInPicture = false
        backgroundSuspended = false
        lastLiveMs = 0L
        analysisBoosted = false
        pausedSinceMs = 0L
        appliedDownloadGateKey = null
        clearNextUp()
        level = Level.Normal
        appliedAnalysisThrottle = 0
        appliedPauseReason = null
        loggedPlayState = "idle"
        setAnalysisPlayerSessionOpen(false)
        setAnalysisThrottleLevel(0)
        transfersActive = false
        setAnalysisTransferActive(false)
        reapplyDownloadCap()
    }
}

private fun com.nuvio.app.features.analysis.AnalysisPowerSnapshot.holdsAnalysis(): Boolean =
    analysisPauseReason(videoPlaying = false, thermal, lowPowerMode) != null
