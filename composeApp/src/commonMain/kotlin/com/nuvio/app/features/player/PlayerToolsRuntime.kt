package com.nuvio.app.features.player

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.skip.FlagSubmitLedger
import com.nuvio.app.features.player.skip.loadSegments
import com.nuvio.app.features.player.skip.segmentRows
import com.nuvio.app.features.player.skip.rememberType
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.flagSubmitContentKey
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

/** Pause copy stays hidden while a tool panel or strip is open, and comes back when it closes. */
internal fun PlayerScreenRuntime.pauseOverlaySuppressed(): Boolean =
    toolChromeOpen() ||
        showQualityPanel ||
        showSpeedPanel ||
        showChaptersPanel ||
        showLiveChannelsPanel ||
        showPartyPanel ||
        showStreamInfo ||
        playerStyleOpen ||
        episodeStreamsPanelState.showStreams

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

internal fun markDraftEpisodeKey(videoId: String?, season: Int?, episode: Int?): String =
    "${videoId.orEmpty()}:$season:$episode"

/** Drop saved clocks when the episode on screen is a different one. */
internal fun PlayerScreenRuntime.syncMarkDraftEpisode() {
    val key = markDraftEpisodeKey(activeVideoId, activeSeasonNumber, activeEpisodeNumber)
    if (key == submitIntroDraftEpisodeKey) return
    submitIntroDraftEpisodeKey = key
    submitIntroDrafts.clear()
    submitIntroTenthDrafts.clear()
    submitIntroUserEdited.clear()
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
 * Flag selects the segment under the playhead, or Intro when nothing is under it.
 * Mark segment opens on Intro. Clocks come from [shownSegmentClocks].
 */
internal fun PlayerScreenRuntime.openMarkingStrip(prefillCurrentSegment: Boolean) {
    markSent = false
    markAbsent = false
    markSubmitError = null
    val allowed = markSegmentTypes(isMoviePlayback)
    submitIntroSegmentType = if (prefillCurrentSegment) {
        val positionSec = playbackClock.positionMs / 1000.0
        val underPlayhead = skipIntervals.firstOrNull { interval ->
            positionSec >= interval.startTime && positionSec <= interval.endTime
        }
        val chosen = underPlayhead ?: skipIntervals.firstOrNull { markSegmentId(it.type) == "intro" }
        val type = chosen?.let { markSegmentId(it.type) } ?: "intro"
        if (type in allowed) type else "intro"
    } else {
        "intro"
    }
    refreshSegmentClocks()
    showPlayerTool(PlayerTool.Marking)
}

internal fun PlayerScreenRuntime.handleSegmentTap(newType: String) {
    when (segmentTap(submitIntroSegmentType, newType, markAbsent)) {
        SegmentTap.Stay -> Unit
        SegmentTap.ReturnToEditing -> {
            markAbsent = false
            markSent = false
            markSubmitError = null
        }
        SegmentTap.Switch -> switchSubmitSegmentType(newType)
    }
}

internal fun PlayerScreenRuntime.resetMarkRange() {
    setMarkClock(start = true, seconds = null)
    setMarkClock(start = false, seconds = null)
    markSubmitError = null
}

internal fun PlayerScreenRuntime.switchSubmitSegmentType(newType: String) {
    syncMarkDraftEpisode()
    val wanted = markSegmentId(newType)
    val old = markSegmentId(submitIntroSegmentType)
    if (wanted == old) return
    if (old in submitIntroUserEdited) {
        submitIntroDrafts[old] = submitIntroStartTimeStr to submitIntroEndTimeStr
        submitIntroTenthDrafts[old] = markStartShowsTenth to markEndShowsTenth
    }
    submitIntroSegmentType = wanted
    markSent = false
    markAbsent = false
    markSubmitError = null
    refreshSegmentClocks()
}

internal fun PlayerScreenRuntime.refreshSegmentClocks(intervals: List<SkipInterval> = skipIntervals) {
    syncMarkDraftEpisode()
    showSegmentClocks(submitIntroSegmentType, intervals)
}

private fun PlayerScreenRuntime.showSegmentClocks(
    segmentType: String,
    intervals: List<SkipInterval>,
) {
    val id = markSegmentId(segmentType)
    val known = intervals.firstOrNull { markSegmentId(it.type) == id }
    val saved = submitIntroDrafts[id]
    val edited = if (id in submitIntroUserEdited) {
        saved?.let { (start, end) -> MarkClocks(parseMarkClock(start), parseMarkClock(end)) }
            ?: MarkClocks(null, null)
    } else {
        null
    }
    val clocks = shownSegmentClocks(
        segmentType = id,
        detectedStartSec = known?.startTime,
        detectedEndSec = known?.endTime,
        edited = edited,
        sameEpisode = true,
    )
    submitIntroStartTimeStr = clocks.startSec?.let(::formatMarkTimestamp).orEmpty()
    submitIntroEndTimeStr = clocks.endSec?.let(::formatMarkTimestamp).orEmpty()
    val tenths = if (id in submitIntroUserEdited) submitIntroTenthDrafts[id] else null
    markStartShowsTenth = tenths?.first == true
    markEndShowsTenth = tenths?.second == true
}

internal fun PlayerScreenRuntime.noteMarkingSubmitted(
    result: IntroSubmitResult,
    countSubmission: Boolean = true,
    recordSharedFlagList: Boolean = true,
) {
    val contentKey = flagSubmitContentKey()
    val canonical = com.nuvio.app.features.player.skip.canonicalFlagSegmentType(result.segmentType)
    val alreadyListed = canonical != null && canonical in FlagSubmitLedger.loadSegments(contentKey)
    if (recordSharedFlagList && (countSubmission || !alreadyListed)) {
        FlagSubmitLedger.rememberType(contentKey, result.segmentType)
    }
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
        val category = com.nuvio.app.features.analysis.SkipLearning.canonicalType(saved.segmentType)
        val hasPrimary = category != null &&
            com.nuvio.app.features.analysis.SkipLearning.userMarks(parentMetaId, activeSeasonNumber, activeEpisodeNumber)[category] != null
        if (countSubmission || !hasPrimary) {
            saveUserSkipMark(
                saved.segmentType,
                saved.startSec,
                saved.endSec,
                submit = false,
                additional = countSubmission && hasPrimary,
            )
        }
    } else if (result.absentSegment) {
        val downloadShowId = com.nuvio.app.features.downloads.downloadOpenedBySource(
            activeSourceUrl.trim(),
            com.nuvio.app.features.downloads.DownloadsRepository.uiState.value.items,
        )?.parentMetaId
        com.nuvio.app.features.player.skip.absentMarkShowIds(parentMetaId, downloadShowId).forEach { showId ->
            com.nuvio.app.features.analysis.SkipLearning.recordAbsent(
                parentMetaId = showId,
                season = activeSeasonNumber,
                episode = activeEpisodeNumber,
                type = result.segmentType,
                durationMs = playbackSnapshot.durationMs,
            )
        }
    }
    val sentType = markSegmentId(result.segmentType)
    val sentCount = com.nuvio.app.features.player.skip.accountSubmissionCount(
        ledgerRows = com.nuvio.app.features.player.skip.OwnTheIntroDbLedger.load(
            com.nuvio.app.features.player.skip.accountScopeForService(
                com.nuvio.app.features.player.skip.IntroSubmitService.THE_INTRODB,
                PlayerSettingsRepository.uiState.value.introDbApiKey,
                PlayerSettingsRepository.uiState.value.theIntroDbApiKey,
            ),
        ).segmentRows(flagSubmitContentKey()),
        type = sentType,
        remoteCount = 0,
    )
    if (com.nuvio.app.features.player.skip.canAcceptSubmission(sentCount)) {
        markSent = false
        submitIntroUserEdited += sentType
        submitIntroDrafts[sentType] = "" to ""
        submitIntroTenthDrafts.remove(sentType)
        refreshSegmentClocks()
    } else {
        markSent = true
    }
    markSubmitError = null
}

private fun PlayerScreenRuntime.flagSubmitContentKey(): String {
    val imdb = activeVideoId?.split(":")?.firstOrNull()?.takeIf { it.startsWith("tt") }
        ?: parentMetaId.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.id?.takeIf { it.startsWith("tt") }
        ?: metaUiState.meta?.imdbId?.takeIf { it.startsWith("tt") }
        ?: ""
    return flagSubmitContentKey(
        imdb,
        activeSeasonNumber ?: 0,
        activeEpisodeNumber ?: 0,
        isMoviePlayback,
    )
}

internal fun PlayerScreenRuntime.setMarkClock(
    start: Boolean,
    seconds: Double?,
    showTenth: Boolean = false,
) {
    syncMarkDraftEpisode()
    val duration = playbackSnapshot.durationMs / 1000.0
    val clamped = seconds?.let { clampMarkTimeToDuration(it, duration.takeIf { it > 0.0 }) }
    val text = clamped?.let(::formatMarkTimestamp).orEmpty()
    val tenth = clamped?.let { markClockShowsTenth(it, showTenth) } == true
    if (start) {
        submitIntroStartTimeStr = text
        markStartShowsTenth = tenth
    } else {
        submitIntroEndTimeStr = text
        markEndShowsTenth = tenth
    }
    val editedId = markSegmentId(submitIntroSegmentType)
    submitIntroUserEdited += editedId
    submitIntroDrafts[editedId] = submitIntroStartTimeStr to submitIntroEndTimeStr
    submitIntroTenthDrafts[editedId] = markStartShowsTenth to markEndShowsTenth
    markSent = false
    markAbsent = absentAfterClockEdit()
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
