package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
/**
 * Opens and closes the player tools without touching the lock.
 * A tool that was already open is cleared first, so two panels never stack.
 */

internal enum class PlayerTool {
    Subs,
    Audio,
    Sources,
    Episodes,
    Info,
    Video,
    Marking,
    Sync,
}

internal fun PlayerScreenRuntime.toolChromeOpen(): Boolean =
    showAudioModal ||
        showSubtitleModal ||
        showVideoSettingsModal ||
        showStreamInfoModal ||
        showSourcesPanel ||
        showEpisodesPanel ||
        showSubmitIntroModal ||
        showSubtitleSyncByEar

internal fun PlayerScreenRuntime.closePlayerTools() {
    restoreMarkPreview()
    val sourcesWereOpen = showSourcesPanel
    val episodesWereOpen = showEpisodesPanel || episodeStreamsPanelState.showStreams
    showAudioModal = false
    showSubtitleModal = false
    showVideoSettingsModal = false
    showStreamInfoModal = false
    showSourcesPanel = false
    showEpisodesPanel = false
    showQualityPanel = false
    showSpeedPanel = false
    showChaptersPanel = false
    showLiveChannelsPanel = false
    showPartyPanel = false
    showSubmitIntroModal = false
    showSubtitleSyncByEar = false
    playerStyleOpen = false
    if (sourcesWereOpen) PlayerStreamsRepository.stopSourcesLoading()
    if (episodesWereOpen) {
        episodeStreamsPanelState = EpisodeStreamsPanelState()
        PlayerStreamsRepository.clearEpisodeStreams()
    }
    subtitleSyncHeardPositionMs = null
    subtitleSyncSawPositionMs = null
    markPreviewEndMs = null
    markPreviewArmed = false
}

internal fun PlayerScreenRuntime.showPlayerTool(tool: PlayerTool) {
    closePlayerTools()
    controlsVisible = false
    when (tool) {
        PlayerTool.Subs -> {
            playerToolTab = PlayerToolTab.Subs
            showSubtitleModal = true
        }
        PlayerTool.Audio -> {
            playerToolTab = PlayerToolTab.Audio
            showAudioModal = true
        }
        PlayerTool.Sources -> {
            playerToolTab = PlayerToolTab.Sources
            showSourcesPanel = true
        }
        PlayerTool.Episodes -> {
            playerToolTab = PlayerToolTab.Episodes
            showEpisodesPanel = true
        }
        PlayerTool.Info -> {
            playerToolTab = PlayerToolTab.Info
            showStreamInfoModal = true
        }
        PlayerTool.Video -> {
            playerToolTab = PlayerToolTab.Video
            showVideoSettingsModal = true
        }
        PlayerTool.Marking -> showSubmitIntroModal = true
        PlayerTool.Sync -> showSubtitleSyncByEar = true
    }
}

/** Back, close, and a tap on the video. The lock stays however the viewer left it. */
internal fun PlayerScreenRuntime.dismissPlayerTools() {
    val next = dismissToolState(playerControlsLocked)
    val restoringPreview = markPreviewRestore != null
    markSubmitGeneration += 1
    if (!restoringPreview) markingPendingSeekMs = null
    closePlayerTools()
    controlsVisible = next.controlsVisible
    clearSubmitIntroForm()
    markSent = false
    markAbsent = false
    markSubmitError = null
}

/** Drop a preview without seeking. Used when the episode itself has changed. */
internal fun PlayerScreenRuntime.abandonMarkPreview() {
    markPreviewRestore = null
    markPreviewEndMs = null
    markPreviewArmed = false
}

internal fun PlayerScreenRuntime.startMarkPreview(startSec: Double, endSec: Double) {
    if (endSec <= startSec) return
    if (markPreviewRestore == null) {
        val playhead = markingPlayheadAfterSample(
            clockMs = playbackClock.positionMs,
            pendingSeekMs = markingPendingSeekMs,
            loadingScrubMs = scrubbingPositionMs,
        )
        markPreviewRestore = markPreviewSession(playhead.positionMs, shouldPlay)
    }
    markPreviewEndMs = (endSec * 1000.0).toLong()
    markPreviewArmed = false
    seekWhileMarking(startSec)
    shouldPlay = true
    playerController?.play()
}

internal fun PlayerScreenRuntime.restoreMarkPreview() {
    val saved = markPreviewRestore ?: return
    markPreviewRestore = null
    markPreviewEndMs = null
    markPreviewArmed = false
    val playback = restoredMarkPreview(saved)
    shouldPlay = playback.shouldPlay
    seekWhileMarking(playback.positionMs / 1000.0)
    if (playback.shouldPlay) playerController?.play() else playerController?.pause()
}

internal fun PlayerScreenRuntime.clearSubmitIntroForm() {
    submitIntroSegmentType = "intro"
    submitIntroStartTimeStr = ""
    submitIntroEndTimeStr = ""
    submitIntroDrafts.clear()
}

internal fun PlayerScreenRuntime.selectToolTab(tab: PlayerToolTab) {
    val wasSources = showSourcesPanel
    val wasEpisodes = showEpisodesPanel || episodeStreamsPanelState.showStreams
    playerToolTab = tab
    playerStyleOpen = false
    showSubtitleModal = tab == PlayerToolTab.Subs
    showAudioModal = tab == PlayerToolTab.Audio
    showSourcesPanel = tab == PlayerToolTab.Sources
    showEpisodesPanel = tab == PlayerToolTab.Episodes
    showStreamInfoModal = tab == PlayerToolTab.Info
    showVideoSettingsModal = tab == PlayerToolTab.Video
    showSubmitIntroModal = false
    showSubtitleSyncByEar = false
    controlsVisible = false
    if (wasSources && tab != PlayerToolTab.Sources) PlayerStreamsRepository.stopSourcesLoading()
    if (wasEpisodes && tab != PlayerToolTab.Episodes) {
        episodeStreamsPanelState = EpisodeStreamsPanelState()
        PlayerStreamsRepository.clearEpisodeStreams()
    }
    if (tab == PlayerToolTab.Sources && !wasSources) loadToolSources()
    if (tab == PlayerToolTab.Episodes) ensurePlayerEpisodesLoaded()
    if (tab == PlayerToolTab.Subs || tab == PlayerToolTab.Audio) refreshTracks()
}

internal fun PlayerScreenRuntime.loadToolSources() {
    val vid = activeVideoId ?: return
    PlayerStreamsRepository.loadSources(
        type = contentType ?: parentMetaType,
        videoId = vid,
        season = activeSeasonNumber,
        episode = activeEpisodeNumber,
    )
}

/**
 * Flag pre-fills the segment under the playhead, or the known intro.
 * Mark segment opens blank. Movie credits never inherit the intro.
 */
internal fun PlayerScreenRuntime.openMarkingStrip(prefillCurrentSegment: Boolean) {
    markSent = false
    markAbsent = false
    markSubmitError = null
    clearSubmitIntroForm()
    if (prefillCurrentSegment) {
        val positionSec = playbackClock.positionMs / 1000.0
        val underPlayhead = skipIntervals.firstOrNull { interval ->
            positionSec >= interval.startTime && positionSec <= interval.endTime
        }
        val chosen = underPlayhead ?: skipIntervals.firstOrNull { markSegmentId(it.type) == "intro" }
        if (chosen != null) {
            val type = markSegmentId(chosen.type)
            val allowed = markSegmentTypes(isMoviePlayback)
            submitIntroSegmentType = if (type in allowed) type else "intro"
            if (submitIntroSegmentType == markSegmentId(chosen.type)) {
                submitIntroStartTimeStr = formatMarkClock(chosen.startTime)
                submitIntroEndTimeStr = formatMarkClock(chosen.endTime)
            }
        }
    }
    showPlayerTool(PlayerTool.Marking)
}

internal fun PlayerScreenRuntime.switchSubmitSegmentType(newType: String) {
    val wanted = markSegmentId(newType)
    val old = markSegmentId(submitIntroSegmentType)
    if (wanted == old) return
    submitIntroDrafts[old] = submitIntroStartTimeStr to submitIntroEndTimeStr
    submitIntroSegmentType = wanted
    markSent = false
    markAbsent = false
    markSubmitError = null
    val draft = submitIntroDrafts[wanted]
    val known = skipIntervals.firstOrNull { markSegmentId(it.type) == wanted }
    val clocks = clocksForSegment(
        segmentType = wanted,
        draft = draft?.let { (start, end) -> MarkClocks(parseMarkClock(start), parseMarkClock(end)) },
        knownStartSec = known?.startTime,
        knownEndSec = known?.endTime,
        knownSegmentType = known?.let { markSegmentId(it.type) },
        isMovie = isMoviePlayback,
    )
    submitIntroStartTimeStr = clocks.startSec?.let(::formatMarkClock).orEmpty()
    submitIntroEndTimeStr = clocks.endSec?.let(::formatMarkClock).orEmpty()
}

internal fun PlayerScreenRuntime.noteMarkingSubmitted(result: IntroSubmitResult) {
    InAppLogger.info(
        "Player/SkipIntro",
        "submit success videoId=${activeVideoId.orEmpty()} s=${activeSeasonNumber ?: 0} " +
            "e=${activeEpisodeNumber ?: 0} type=${result.segmentType} " +
            "absent=${result.absentSegment}",
    )
    val currentVideoId = activeVideoId.orEmpty()
    if (currentVideoId.isNotBlank()) {
        submittedSegmentTypesByVideoId.getOrPut(currentVideoId) { mutableSetOf() }
            .add(result.segmentType)
    }
    val saved = submittedSkipMarkToSave(result)
    if (saved != null) {
        saveUserSkipMark(saved.segmentType, saved.startSec, saved.endSec, submit = false)
    }
    markSent = true
    markSubmitError = null
}

internal fun PlayerScreenRuntime.setMarkClock(start: Boolean, seconds: Double?) {
    val text = seconds?.let(::formatMarkClock).orEmpty()
    if (start) submitIntroStartTimeStr = text else submitIntroEndTimeStr = text
    submitIntroDrafts[markSegmentId(submitIntroSegmentType)] = submitIntroStartTimeStr to submitIntroEndTimeStr
    markSent = false
}

internal fun PlayerScreenRuntime.seekWhileMarking(seconds: Double) {
    if (!markingSeekAllowed(playbackSnapshot.durationMs / 1000.0)) return
    val requested = (seconds.coerceAtLeast(0.0) * 1000.0).toLong()
    val target = if (isPartyControlLocked) ownSeekTarget(requested) ?: return else requested
    markingPendingSeekMs = target
    finishTimelineScrub(target)
    noteSeek()
    playerController?.seekTo(target)
    scheduleProgressSyncAfterSeek()
}

internal fun PlayerScreenRuntime.openSidePanel(apply: () -> Unit) {
    closePlayerTools()
    controlsVisible = false
    apply()
}
