package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.FinishedDownloadDeletion
import com.nuvio.app.features.downloads.InAppPlaybackKeepAlive
import com.nuvio.app.features.downloads.hasStoredFingerprint
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource
import com.nuvio.app.core.serialization.LenientJson

/** What the platform analyzer returns for one file (see MediaAnalyzer.swift). */
@Serializable
internal data class RawMediaAnalysis(
    val durationMs: Long = 0L,
    val energyStepMs: Int = 100,
    val energy: String? = null,
    val integratedLufs: Double? = null,
    val fingerprintStepMs: Int = 100,
    val head: String? = null,
    val tail: String? = null,
    val tailStartMs: Long = 0L,
    /** Monotonic milliseconds. Not stored with the analysis; see [AnalysisStageTimings]. */
    val decodeMs: Long = 0L,
    val sampleLoopMs: Long = 0L,
    val fingerprintMs: Long = 0L,
    /** Chapter markers read from the container. Not stored on [MediaAnalysisResult]. */
    val chapters: List<RawChapter> = emptyList(),
    /** Milliseconds spent reading those markers, from the same monotonic clock as the other stages. */
    val chaptersMs: Long = 0L,
    /** Milliseconds spent sleeping in the analysis throttle. Included in [decodeMs]. */
    val throttledMs: Long = 0L,
    /** Throttle level when packet reading started. 0 full speed, 1 playing, 2 struggling. */
    val throttleLevelStart: Int = 0,
    /** How many times the throttle check ran during the decode. */
    val throttleYields: Int = 0,
    /** Analyser thread QoS class name. Empty when the platform did not report one. */
    val qos: String = "",
)

internal fun RawMediaAnalysis.toStoredResult(
    key: String,
    fileName: String?,
    analyzedAtMs: Long,
    userSegments: Map<String, UserSegment>,
): MediaAnalysisResult = MediaAnalysisResult(
    key = key,
    analyzedAtMs = analyzedAtMs,
    durationMs = durationMs,
    fileName = fileName,
    energyStepMs = energyStepMs,
    energy = energy,
    integratedLufs = integratedLufs,
    fingerprintStepMs = fingerprintStepMs,
    headFingerprint = head,
    tailFingerprint = tail,
    tailStartMs = tailStartMs,
    userSegments = userSegments,
)

/** Platform decoder (iOS: FFmpeg from the player's engine). */
internal expect object MediaAnalyzerPlatform {
    val supported: Boolean
    /** Analyses the local file at [fileUri]; null when it could not be read or was cancelled. */
    suspend fun analyze(fileUri: String): String?
    fun cancel()
    fun isCharging(): Boolean
    /** Progress of the running analyze/makeThumbnails call, 0..1 (negative: unknown). */
    fun progress(): Double
    /** Why the last analyze/makeThumbnails call returned null, in short words. */
    fun lastError(): String?
    /**
     * Writes scrub-preview thumbnail sheets of [fileUri] to `<outputPrefix><n>.jpg` and returns
     * their layout as JSON ([RawThumbnailSheets]); null when it failed or was cancelled.
     */
    suspend fun makeThumbnails(fileUri: String, outputPrefix: String, durationMs: Long, intervalMs: Long, tileWidth: Int): String?
    /**
     * Fingerprints [startMs]..[endMs] of a remote stream, in memory only, as JSON ([RawClipFingerprint]);
     * null when it failed or was cancelled.
     */
    suspend fun fingerprintClip(url: String, headers: Map<String, String>, startMs: Long, endMs: Long): String?
}

@Serializable
internal data class RawClipFingerprint(
    val startMs: Long = 0L,
    val stepMs: Int = 100,
    val fingerprint: String? = null,
)

@Serializable
internal data class RawThumbnailSheets(
    val count: Int = 0,
    val tileWidth: Int = 0,
    val tileHeight: Int = 0,
    val columns: Int = 0,
    val rows: Int = 0,
    val sheets: Int = 0,
)

/**
 * Runs the on-device analysis of finished downloads one file at a time: after a download
 * finishes, at app launch, and from the iOS background processing task. Waits while something
 * is playing in the app, honours "only while charging", and can be cancelled at any point
 * ([cancel], or the background task expiring).
 */
internal object MediaAnalysisScheduler {
    private const val START_DELAY_MS = 20_000L
    private const val PLAYBACK_POLL_MS = 15_000L
    private const val CHARGER_POLL_MS = 60_000L
    private const val MAX_SIBLINGS = 4
    private const val THUMBNAIL_TARGET_COUNT = 400L
    private const val THUMBNAIL_MIN_INTERVAL_MS = 5_000L
    private const val THUMBNAIL_MAX_INTERVAL_MS = 20_000L
    private const val THUMBNAIL_TILE_WIDTH = 160

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = LenientJson
    private var job: Job? = null
    private val runMutex = Mutex()
    private val runAgain = atomic(false)

    /** Test stand-in for [MediaAnalyzerPlatform.analyze]. Null calls the platform. */
    internal var analyzeFileForTest: (suspend (String) -> String?)? = null

    /** Test stand-in for [MediaAnalyzerPlatform.supported]. */
    internal var supportedOverride: Boolean? = null

    private fun analyserSupported(): Boolean = supportedOverride ?: MediaAnalyzerPlatform.supported

    /** True when a pass can be started. The season overview uses this before it keeps a temp file. */
    fun acceptsWork(): Boolean = analyserSupported() && anyFeatureEnabled

    internal fun resetForTest() {
        job?.cancel()
        job = null
        retryKick?.cancel()
        retryKick = null
        failedThisSession.clear()
        retries.clear()
        runAgain.value = false
        analyzeFileForTest = null
        supportedOverride = null
        priorityItemId = null
        playbackStrainExemptId = null
        currentItem = null
    }

    private val anyFeatureEnabled: Boolean
        get() = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.SubtitleSync) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.Loudness) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.ScrubPreviews) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection)

    /** Starts a pass over finished downloads unless one is already running. */
    fun kick(startDelayMs: Long = START_DELAY_MS) {
        if (!analyserSupported()) return
        if (!runMutex.tryLock()) {
            runAgain.value = true
            return
        }
        runMutex.unlock()
        if (job?.isActive == true) {
            runAgain.value = true
            return
        }
        startJob(startDelayMs)
    }

    private fun startJob(startDelayMs: Long) {
        job = scope.launch {
            val self = kotlin.coroutines.coroutineContext[Job]
            MediaAnalysisStatus.setJob(AnalysisJobState.Starting)
            try {
                delay(startDelayMs)
                runPending(fromBackgroundTask = false)
            } finally {
                if (job === self && MediaAnalysisStatus.job.value !is AnalysisJobState.WaitingForCharger) {
                    MediaAnalysisStatus.setJob(AnalysisJobState.Idle)
                }
            }
        }
    }

    /** "Analyse now": forget this session's failures and start at once (also replaces a pending start). */
    fun analyseNow() {
        failedThisSession.clear()
        retries.clear()
        MediaAnalysisStatus.clearAllFailures()
        clearStoredAttempts()
        val running = MediaAnalysisStatus.job.value is AnalysisJobState.Running
        if (!running) {
            job?.cancel()
            job = null
        }
        kick(startDelayMs = 0L)
    }

    /** Drops the stored results of [items] and analyses them again. */
    fun reanalyse(items: List<DownloadItem>) {
        val runningId = (MediaAnalysisStatus.job.value as? AnalysisJobState.Running)?.itemId
        if (runningId != null && items.any { it.id == runningId }) cancel()
        items.forEach { item ->
            val key = MediaAnalysisStore.keyFor(item)
            MediaAnalysisStore.reset(key)
            failedThisSession -= item.id
            MediaAnalysisStatus.clearFailure(item.id)
        }
        MediaAnalysisStatus.resultsChanged()
        analyseNow()
    }

    /** The episode being watched goes next (or now, replacing another file mid-way). */
    private var priorityItemId: String? = null
    private var playbackStrainExemptId: String? = null

    fun prioritiseForPendingDeletion(item: DownloadItem) {
        playbackStrainExemptId = item.id
        retries.remove(item.id)
        prioritise(item)
    }

    fun prioritise(item: DownloadItem) {
        priorityItemId = item.id
        failedThisSession -= item.id
        val runningId = (MediaAnalysisStatus.job.value as? AnalysisJobState.Running)?.itemId
        if (runningId == item.id) return
        if (runningId != null) cancel()
        if (job?.isActive != true) kick(startDelayMs = 0L) else {
            job?.cancel(); job = null; kick(startDelayMs = 0L)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        MediaAnalyzerPlatform.cancel()
    }

    /**
     * [downloadId] is being deleted: stop the run that reads it, so the analyser closes the file
     * (iOS frees a deleted file's space only when nothing has it open), and carry on with the rest.
     */
    fun releaseFile(downloadId: String) {
        if (playbackStrainExemptId == downloadId) playbackStrainExemptId = null
        val runningId = (MediaAnalysisStatus.job.value as? AnalysisJobState.Running)?.itemId
        if (runningId != downloadId && currentItem?.id != downloadId) return
        if (priorityItemId == downloadId) priorityItemId = null
        cancel()
        kick()
    }

    /** True when every pending file was analysed (false: stopped, cancelled or not allowed). */
    suspend fun runPending(fromBackgroundTask: Boolean): Boolean {
        if (!runMutex.tryLock()) {
            runAgain.value = true
            return false
        }
        try {
            var finished: Boolean
            do {
                runAgain.value = false
                finished = runPendingOnce(fromBackgroundTask)
            } while (runAgain.value && scopeActive())
            return finished
        } finally {
            val followUp = runAgain.getAndSet(false)
            runMutex.unlock()
            // This coroutine may still be the active job, so kick() would only set the flag again.
            if (followUp) startJob(startDelayMs = 0L)
        }
    }

    private suspend fun runPendingOnce(fromBackgroundTask: Boolean): Boolean {
        if (!analyserSupported() || !anyFeatureEnabled) return true
        DownloadsRepository.ensureLoaded()
        forgetDeletedFiles()
        while (true) {
            if (!scopeActive()) return false
            if (MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.OnlyWhileCharging) &&
                !MediaAnalyzerPlatform.isCharging()
            ) {
                InAppLogger.debug("Analysis", "waiting for charger")
                MediaAnalysisStatus.setJob(AnalysisJobState.WaitingForCharger)
                // In the app, keep checking: nothing else starts the queue when the charger goes in.
                if (fromBackgroundTask) return false
                delay(CHARGER_POLL_MS)
                continue
            }
            // Playback no longer stops analysis; it runs throttled (see PlaybackPriority). Only a
            // struggling player makes it wait before the next file.
            if (!fromBackgroundTask &&
                !com.nuvio.app.features.downloads.PlaybackPriority.analysisBoosted &&
                com.nuvio.app.features.downloads.PlaybackPriority.level ==
                com.nuvio.app.features.downloads.PlaybackPriority.Level.Strained &&
                !strainExemptReady()
            ) {
                MediaAnalysisStatus.setJob(AnalysisJobState.WaitingForPlayback)
                delay(PLAYBACK_POLL_MS)
                continue
            }
            val item = nextPending() ?: run {
                MediaAnalysisStatus.setJob(AnalysisJobState.Idle)
                scheduleRetryKick()
                return true
            }
            currentItem = item
            currentInBackground = fromBackgroundTask
            val pass = try {
                analyzeOne(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                MediaAnalysisStatus.recordFailure(item, ANALYSIS_STOPPED_REASON)
                PassResult.Retry
            } finally {
                currentItem = null
                MediaAnalysisStatus.setJob(AnalysisJobState.Idle)
                MediaAnalysisStatus.resultsChanged()
            }
            if (pass == PassResult.Cancelled || !scopeActive()) return false
            val prior = resolveAnalysisAttempts(MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item)), item.fileName)
            val kind = classifyAnalysisAttempt(
                scopeActive = pass != PassResult.Cancelled,
                analyserFailed = pass == PassResult.Retry,
                clipUnavailable = pass == PassResult.ClipRetry,
            )
            val update = updateAnalysisAttempts(prior, kind, com.nuvio.app.features.streams.epochMs())
            if (update.write) writeAttempts(item, update.attempts)
            val terminalFailure = update.giveUp
            if (pass == PassResult.Done) {
                MediaAnalysisStatus.clearFailure(item.id)
                retries -= item.id
            } else if (terminalFailure) {
                // Try again later instead of skipping the file for the rest of the session: it
                // used to stay "partly analysed, queued for more" until Analyse now was tapped.
                // After a few tries it is left alone and shown as failed, with the reason.
                retries -= item.id
                failedThisSession += item.id
                if (MediaAnalysisStatus.statusFor(item) !is ItemAnalysisStatus.Failed) {
                    MediaAnalysisStatus.recordFailure(item, MediaAnalyzerPlatform.lastError() ?: "Analysis stopped")
                }
            } else {
                val notBefore = update.retryNotBeforeMs ?: (com.nuvio.app.features.streams.epochMs() + (analysisRetryDelayMs(1) ?: 0L))
                retries[item.id] = update.attempts to notBefore
            }
            val savedFingerprint = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item)).hasStoredFingerprint()
            val clipStillPending = pass == PassResult.ClipRetry && !terminalFailure
            if (playbackStrainExemptId == item.id && (pass == PassResult.Done || terminalFailure || savedFingerprint) && !clipStillPending) {
                playbackStrainExemptId = null
            }
            // A stored fingerprint normally releases the hold. A sibling clip that could not be read
            // is not finished, so the file stays until that try succeeds or the attempts run out.
            if ((pass == PassResult.Done || terminalFailure || savedFingerprint) && !clipStillPending) {
                FinishedDownloadDeletion.analysisSettled(item.id)
            }
        }
    }

    private enum class PassResult { Done, Retry, ClipRetry, Cancelled }

    private fun clearStoredAttempts() {
        DownloadsRepository.uiState.value.items.forEach { item ->
            val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item)) ?: return@forEach
            if (stored.fileName == item.fileName && stored.analysisAttempts != 0) {
                MediaAnalysisStore.save(stored.copy(analysisAttempts = 0))
            }
        }
    }

    /** Keeps the fingerprint already stored for this file and writes [attempts]. */
    private fun writeAttempts(item: DownloadItem, attempts: Int) {
        val key = MediaAnalysisStore.keyFor(item)
        val stored = MediaAnalysisStore.load(key)
        val base = if (stored != null && stored.fileName == item.fileName) {
            stored
        } else {
            MediaAnalysisResult(
                key = key,
                analyzedAtMs = com.nuvio.app.features.streams.epochMs(),
                durationMs = 0L,
                fileName = item.fileName,
                userSegments = stored?.userSegments.orEmpty(),
            )
        }
        MediaAnalysisStore.save(base.copy(fileName = item.fileName, analysisAttempts = attempts))
    }

    private fun strainExemptReady(): Boolean {
        val id = playbackStrainExemptId ?: return false
        if (id in failedThisSession || isBackingOff(id)) return false
        return DownloadsRepository.uiState.value.items.any { it.id == id && it.status == DownloadStatus.Completed }
    }

    private val failedThisSession = mutableSetOf<String>()
    /** id -> (attempts so far, not before epoch ms). */
    private val retries = mutableMapOf<String, Pair<Int, Long>>()
    private var retryKick: Job? = null

    private fun isBackingOff(id: String): Boolean =
        (retries[id]?.second ?: 0L) > com.nuvio.app.features.streams.epochMs()

    /** Files waiting out a retry delay get a pass of their own when it is over. */
    private fun scheduleRetryKick() {
        val next = retries.values.minOfOrNull { it.second } ?: return
        retryKick?.cancel()
        retryKick = scope.launch {
            delay((next - com.nuvio.app.features.streams.epochMs()).coerceAtLeast(1_000L))
            kick(startDelayMs = 0L)
        }
    }
    private var currentItem: DownloadItem? = null
    private var currentInBackground = false

    private fun stage(stage: AnalysisStage, activity: String = activityFor(stage)) {
        val item = currentItem ?: return
        val title = buildString {
            append(item.title)
            if (item.seasonNumber != null && item.episodeNumber != null) append(" S${item.seasonNumber}E${item.episodeNumber}")
        }
        val since = com.nuvio.app.features.streams.epochMs()
        MediaAnalysisStatus.setJob(
            AnalysisJobState.Running(
                item.id,
                title,
                stage,
                if (stage == AnalysisStage.Audio || stage == AnalysisStage.Previews) 0.0 else -1.0,
                currentInBackground,
                activity = activity,
                activitySinceEpochMs = since,
            ),
        )
        InAppLogger.info("Analysis", "activity id=${item.id} $activity")
    }

    /** Same stage, new step. The percent stays. The clock starts again for this step. */
    private fun noteActivity(activity: String) {
        val current = MediaAnalysisStatus.job.value as? AnalysisJobState.Running
        if (current == null) {
            stage(AnalysisStage.Matching, activity)
            return
        }
        if (current.activity == activity) return
        MediaAnalysisStatus.setJob(
            current.copy(activity = activity, activitySinceEpochMs = com.nuvio.app.features.streams.epochMs()),
        )
        InAppLogger.info("Analysis", "activity id=${current.itemId} $activity")
    }

    private fun activityFor(stage: AnalysisStage): String = when (stage) {
        AnalysisStage.Audio -> "audio"
        AnalysisStage.Matching -> "match"
        AnalysisStage.Recap -> "recap"
        AnalysisStage.Previews -> "previews"
    }

    /**
     * Runs [block] while copying the platform analyzer's progress into the status every half second.
     * A long decode is left running. The percent moves only when the platform reports a new value.
     */
    private suspend fun <T> withProgress(block: suspend () -> T): T? {
        // The test stand-in does not report progress, and a poller would stall the host test clock.
        if (analyzeFileForTest != null) return block()
        return kotlinx.coroutines.coroutineScope {
            val poller = launch {
                var last = Double.NaN
                while (isActive) {
                    delay(500)
                    val progress = MediaAnalyzerPlatform.progress()
                    if (analysisProgressMoved(last, progress)) {
                        last = progress
                        MediaAnalysisStatus.updateProgress(progress)
                    }
                }
            }
            try {
                block()
            } finally {
                poller.cancel()
            }
        }
    }

    private suspend fun scopeActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun nextPending(): DownloadItem? = DownloadsRepository.uiState.value.items
        .filter { analysisAcceptsDownload(it.status) && it.id !in failedThisSession && !isBackingOff(it.id) }
        .sortedWith(compareBy<DownloadItem> { if (it.id == priorityItemId) 0 else 1 }.then(AnalysisOrder))
        .firstOrNull { item ->
            val existing = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item))
            shouldQueueAnalysis(
                stored = existing,
                fileName = item.fileName,
                needsDerivedWork = existing == null || existing.fileName != item.fileName || needsDerived(existing) ||
                    shouldRecapture(existing, MediaAnalyzerPlatform.supported),
                backingOff = false,
            )
        }

    private fun needsDerived(result: MediaAnalysisResult): Boolean =
        result.derivedVersion < MEDIA_ANALYSIS_DERIVED_VERSION ||
            (MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.ScrubPreviews) && result.thumbnails == null)

    private fun forgetDeletedFiles() {
        val liveFiles = DownloadsRepository.uiState.value.items.map { it.fileName }.toSet()
        // Only the keys of current downloads are cheap to enumerate; stale ones are cleared when seen.
        DownloadsRepository.uiState.value.items.forEach { item ->
            val key = MediaAnalysisStore.keyFor(item)
            val stored = MediaAnalysisStore.load(key) ?: return@forEach
            if (stored.fileName != null && stored.fileName !in liveFiles) MediaAnalysisStore.forgetFileData(key)
        }
    }

    private suspend fun analyzeOne(item: DownloadItem): PassResult {
        val uri = DownloadsRepository.playableLocalFileUri(item) ?: run {
            MediaAnalysisStatus.recordFailure(item, "The file is not on this device")
            return if (scopeActive()) PassResult.Retry else PassResult.Cancelled
        }
        if (!uri.startsWith("file:")) {
            MediaAnalysisStatus.recordFailure(item, "The file is not on this device")
            return if (scopeActive()) PassResult.Retry else PassResult.Cancelled
        }
        val stored = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item))
            ?.takeIf { it.fileName == item.fileName && it.hasStoredFingerprint() }
        val base: MediaAnalysisResult
        val trace: SegmentTrace
        if (stored != null && !stored.needsSiblingClipRetry() && !shouldRecapture(stored, MediaAnalyzerPlatform.supported)) {
            base = stored
            trace = SegmentTrace()
        } else {
            val audio = audioAnalysis(item, uri, stored) ?: return if (scopeActive()) PassResult.Retry else PassResult.Cancelled
            if (!scopeActive()) return PassResult.Cancelled
            if (audio.clipUnavailable) return PassResult.ClipRetry
            base = audio.result
            trace = audio.trace
        }
        if (!scopeActive()) return PassResult.Cancelled
        return if (derivedAnalysis(item, uri, base, trace)) PassResult.Done else {
            if (scopeActive()) PassResult.Retry else PassResult.Cancelled
        }
    }

    /** Recap, preview, and scrub-preview thumbnails, from a stored analysis and the file. */
    private suspend fun derivedAnalysis(
        item: DownloadItem,
        uri: String,
        base: MediaAnalysisResult,
        trace: SegmentTrace,
    ): Boolean {
        var result = base
        val season = item.seasonNumber
        val episodeNumber = item.episodeNumber
        val introOn = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection)
        val recapOn = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection)
        if (season != null && episodeNumber != null) {
            val siblings = MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, result.key)
            val priors = cachedSegmentPriors(item.parentMetaId, season, episodeNumber, result.durationMs)
            // A file that already had a fingerprint skipped the audio match. Re-vote the intro
            // only when that pass did not already record one and none is stored.
            if (introOn && !trace.has("intro") && result.introEndMs == null && siblings.isNotEmpty()) {
                stage(AnalysisStage.Matching)
                val capped = siblings.sortedByDescending { it.analyzedAtMs }.take(MAX_SIBLINGS)
                result = withContext(Dispatchers.Default) {
                    result.applyingSeasonDetection(capped, priors.intro, priors.credits, trace)
                }
            }
            val recapMark = result.userSegments["recap"]
                ?.takeIf { com.nuvio.app.features.player.sameCut(it.durationMs, result.durationMs) }
            when {
                !recapOn -> trace.replace("recap", detectionOffDecision("recap"))
                "recap" in result.chapterTypes ->
                    trace.replace("recap", chapterDecision("recap", result.recapStartMs, result.recapEndMs))
                recapMark != null -> trace.replace("recap", userMarkDecision("recap", recapMark.startMs, recapMark.endMs))
                else -> {
                    stage(AnalysisStage.Recap)
                    val recap = withContext(Dispatchers.Default) {
                        RecapDetector.detect(result, siblings, trace, priors.recap)
                    }
                    // A chapter recap stays. Otherwise this is the same assignment as before,
                    // including clearing a recap the search did not find again.
                    result = result.withDetectedRecap(recap)
                }
            }
            val previewMark = result.userSegments["preview"]
                ?.takeIf { com.nuvio.app.features.player.sameCut(it.durationMs, result.durationMs) }
            when {
                "preview" in result.chapterTypes ->
                    trace.replace("preview", chapterDecision("preview", result.previewStartMs, result.previewEndMs))
                previewMark != null ->
                    trace.replace("preview", userMarkDecision("preview", previewMark.startMs, previewMark.endMs))
                else -> {
                    noteActivity("preview")
                    val preview = withContext(Dispatchers.Default) {
                        PreviewDetector.detect(result, siblings, trace, priors.preview)
                    }
                    result = result.withDetectedPreview(preview)
                }
            }
        }
        val isEpisode = season != null && episodeNumber != null
        if (!trace.has("intro")) {
            trace.add(
                if (isEpisode && !introOn) detectionOffDecision("intro")
                else missingDecision("intro", result.introStartMs, result.introEndMs),
            )
        }
        if (!trace.has("credits")) {
            trace.add(
                if (isEpisode && !introOn) detectionOffDecision("credits")
                else missingDecision("credits", result.creditsStartMs, result.creditsEndMs),
            )
        }
        if (!trace.has("recap")) {
            trace.add(
                if (isEpisode && !recapOn) detectionOffDecision("recap")
                else missingDecision("recap", result.recapStartMs, result.recapEndMs),
            )
        }
        if (!trace.has("preview")) trace.add(missingDecision("preview", result.previewStartMs, result.previewEndMs))
        // Logged before thumbnails so a thumbnail failure still leaves the four segment lines.
        logSegmentDecisions(item.id, trace.decisions)
        var thumbsOk = true
        if (MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.ScrubPreviews) && result.thumbnails == null &&
            result.durationMs > 0L
        ) {
            val intervalMs = (result.durationMs / THUMBNAIL_TARGET_COUNT).coerceIn(THUMBNAIL_MIN_INTERVAL_MS, THUMBNAIL_MAX_INTERVAL_MS)
            val prefix = MediaAnalysisFiles.binaryPath("${result.key}_thumbs_")
            stage(AnalysisStage.Previews)
            val text = prefix?.let { withProgress { MediaAnalyzerPlatform.makeThumbnails(uri, it, result.durationMs, intervalMs, THUMBNAIL_TILE_WIDTH) } }
            val raw = text?.let { runCatching { json.decodeFromString(RawThumbnailSheets.serializer(), it) }.getOrNull() }
            if (raw != null && raw.count > 0 && raw.sheets > 0) {
                result = result.copy(
                    thumbnails = ThumbnailSheets(
                        intervalMs = intervalMs,
                        count = raw.count,
                        tileWidth = raw.tileWidth,
                        tileHeight = raw.tileHeight,
                        columns = raw.columns,
                        rows = raw.rows,
                        sheets = raw.sheets,
                    ),
                )
                InAppLogger.info("Analysis", "thumbnails id=${item.id} count=${raw.count} sheets=${raw.sheets}")
            } else {
                thumbsOk = false
                InAppLogger.warn("Analysis", "thumbnails failed id=${item.id}")
                if (scopeActive()) {
                    MediaAnalysisStatus.recordFailure(item, "Previews: " + (MediaAnalyzerPlatform.lastError() ?: "could not be made"))
                }
            }
        }
        if (!scopeActive()) {
            // Stopped (the download is being deleted): keep the recap already found; the step
            // counts as not done, so it runs again if the file comes back.
            MediaAnalysisStore.save(result)
            return false
        }
        MediaAnalysisStore.save(result.copy(derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION))
        return thumbsOk
    }

    private data class AudioPass(
        val result: MediaAnalysisResult,
        val clipUnavailable: Boolean,
        val trace: SegmentTrace,
    )

    /**
     * [already] is a stored fingerprint for this same file. It is reused for a sibling-clip retry.
     * A print from the shorter capture window is decoded again when this device can analyse audio.
     * Null means the audio pass still has to run.
     */
    private suspend fun audioAnalysis(item: DownloadItem, uri: String, already: MediaAnalysisResult?): AudioPass? {
        val started = TimeSource.Monotonic.markNow()
        var matchMs = 0L
        var clipMs = 0L
        var teachMs = 0L
        var backfillMs = 0L
        suspend fun <T> timed(add: (Long) -> Unit, block: suspend () -> T): T {
            val mark = TimeSource.Monotonic.markNow()
            try {
                return block()
            } finally {
                add(mark.elapsedNow().inWholeMilliseconds)
            }
        }
        var decodeMs = 0L
        var sampleLoopMs = 0L
        var fingerprintMs = 0L
        var rawChapters: List<RawChapter> = emptyList()
        var rawChaptersMs = 0L
        var throttledMs = 0L
        var throttleLevelStart = 0
        var throttleYields = 0
        var qos = ""
        val key = MediaAnalysisStore.keyFor(item)
        val trace = SegmentTrace()
        val previous = already ?: MediaAnalysisStore.load(key)
        val recapture = shouldRecapture(already, MediaAnalyzerPlatform.supported)
        var result = if (already != null && already.fileName == item.fileName && already.hasStoredFingerprint() && !recapture) {
            already
        } else {
            InAppLogger.info("Analysis", "analyse start id=${item.id} file=${item.fileName}")
            stage(AnalysisStage.Audio)
            ChapterSkipClassifier.prepare(key, item.parentMetaId, item.seasonNumber ?: -1)
            val text = try {
                withProgress {
                    val hook = analyzeFileForTest
                    if (hook != null) hook(uri) else MediaAnalyzerPlatform.analyze(uri)
                }
            } finally {
                ChapterSkipClassifier.clear()
            } ?: run {
                InAppLogger.warn("Analysis", "analyse failed id=${item.id}")
                if (scopeActive()) {
                    MediaAnalysisStatus.recordFailure(
                        item,
                        MediaAnalyzerPlatform.lastError() ?: "Could not analyse the audio",
                    )
                }
                return null
            }
            val raw = runCatching { json.decodeFromString(RawMediaAnalysis.serializer(), text) }.getOrNull() ?: run {
                MediaAnalysisStatus.recordFailure(item, "The analyser returned unreadable data")
                return null
            }
            decodeMs = raw.decodeMs
            sampleLoopMs = raw.sampleLoopMs
            fingerprintMs = raw.fingerprintMs
            rawChapters = raw.chapters
            rawChaptersMs = raw.chaptersMs
            throttledMs = raw.throttledMs
            throttleLevelStart = raw.throttleLevelStart
            throttleYields = raw.throttleYields
            qos = raw.qos
            MediaAnalysisResult(
                key = key,
                analyzedAtMs = com.nuvio.app.features.streams.epochMs(),
                durationMs = raw.durationMs,
                fileName = item.fileName,
                energyStepMs = raw.energyStepMs,
                energy = raw.energy,
                integratedLufs = raw.integratedLufs,
                fingerprintStepMs = raw.fingerprintStepMs,
                headFingerprint = raw.head,
                tailFingerprint = raw.tail,
                tailStartMs = raw.tailStartMs,
                userSegments = previous?.userSegments.orEmpty(),
                rejectedSegments = previous?.rejectedSegments.orEmpty(),
                analysisAttempts = resolveAnalysisAttempts(previous, item.fileName),
            )
        }
        var clipUnavailable = false
        val season = item.seasonNumber
        val episodeNumber = item.episodeNumber
        if (season != null && episodeNumber != null &&
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection)
        ) {
            val priors = cachedSegmentPriors(item.parentMetaId, season, episodeNumber, result.durationMs)
            val siblings = MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, key)
                .sortedByDescending { it.analyzedAtMs }
                .take(MAX_SIBLINGS)
            result = if (siblings.isNotEmpty()) {
                stage(AnalysisStage.Matching)
                InAppLogger.info(
                    "Analysis",
                    "match id=${item.id} siblings=${siblings.size} headChars=${result.headFingerprint?.length ?: 0} " +
                        "tailChars=${result.tailFingerprint?.length ?: 0}",
                )
                timed({ matchMs += it }) {
                    withContext(Dispatchers.Default) {
                        result.applyingSeasonDetection(siblings, priors.intro, priors.credits, trace)
                    }
                }
            } else {
                val remembered = result.rememberingSilentRanges()
                SeasonSegmentDetector.detect(
                    remembered,
                    emptyList(),
                    theIntroDbIntro = priors.intro,
                    theIntroDbCredits = priors.credits,
                    trace = trace,
                )
                remembered
            }
            // Episodes the user marked are trusted references: they beat the pairwise guess.
            val references = MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, key)
                .filter { it.userSegments.containsKey("intro") || it.userSegments.containsKey("outro") }
            if (references.isNotEmpty()) {
                stage(AnalysisStage.Matching)
                result = timed({ matchMs += it }) {
                    withContext(Dispatchers.Default) { SkipLearning.learnFromReferences(result, references) }
                }
            }
            // No stored episode to match: fingerprint a clip of the nearest episode with known times.
            if (siblings.isEmpty() && references.isEmpty()) {
                stage(AnalysisStage.Matching, "clip")
                InAppLogger.info("Analysis", "clip id=${item.id} headChars=${result.headFingerprint?.length ?: 0}")
                val clip = timed({ clipMs += it }) {
                    SiblingClipReference.apply(item, result)
                }
                result = clip.result
                clipUnavailable = clip.attempt == SiblingClipAttempt.Unavailable
            }
        } else if (season != null && episodeNumber != null) {
            trace.add(detectionOffDecision("intro"))
            trace.add(detectionOffDecision("credits"))
        }
        val chapterMark = TimeSource.Monotonic.markNow()
        val protected = result.userSegments.keys + result.learnedTypes + result.foundUsing.keys
        val introDetectionOn = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection)
        val recapDetectionOn = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection)
        val fileChapters = rawChapters.map { FileChapter(it.title, it.startMs, it.endMs) }
        result = result.applyingChapterSegments(
            chapters = fileChapters,
            durationMs = result.durationMs,
            protectedKinds = protected,
            introDetection = introDetectionOn,
            recapDetection = recapDetectionOn,
        )
        if ("intro" in result.chapterTypes) {
            trace.replace("intro", chapterDecision("intro", result.introStartMs, result.introEndMs))
        }
        if ("outro" in result.chapterTypes) {
            trace.replace("credits", chapterDecision("credits", result.creditsStartMs, result.creditsEndMs))
        }
        if ("recap" in result.chapterTypes) {
            trace.replace("recap", chapterDecision("recap", result.recapStartMs, result.recapEndMs))
        }
        if ("preview" in result.chapterTypes) {
            trace.replace("preview", chapterDecision("preview", result.previewStartMs, result.previewEndMs))
        }
        val chaptersMs = rawChaptersMs.coerceAtLeast(0L) + chapterMark.elapsedNow().inWholeMilliseconds
        val chapterSource = if (result.chapterTypes.isEmpty()) "-" else com.nuvio.app.features.player.CHAPTER_SKIP_PROVIDER
        result = saveFingerprint(item, result) { backfillMs += it }
        // The season back-fill can re-run detection on this episode; chapter times outrank it.
        if (fileChapters.isNotEmpty() && result.chapterTypes.isNotEmpty()) {
            val again = result.applyingChapterSegments(
                chapters = fileChapters,
                durationMs = result.durationMs,
                protectedKinds = protected,
                introDetection = introDetectionOn,
                recapDetection = recapDetectionOn,
            )
            if (again != result) {
                MediaAnalysisStore.save(again)
                result = again
            }
        }
        // A mark made before this file was analysed now has fingerprints: teach the season.
        if (season != null && item.episodeNumber != null &&
            (result.userSegments.containsKey("intro") || result.userSegments.containsKey("outro"))
        ) {
            noteActivity("teach")
            InAppLogger.info("Analysis", "teach id=${item.id} season=$season")
            timed({ teachMs += it }) {
                SkipLearning.teachSeason(item.parentMetaId, season, key)
            }
        }
        InAppLogger.info(
            "Analysis",
            "analyse done id=${item.id} durationMs=${result.durationMs} lufs=${result.integratedLufs} " +
                "intro=${result.introStartMs}-${result.introEndMs} credits=${result.creditsStartMs}-${result.creditsEndMs}",
        )
        logAnalysisStageTimings(
            item.id,
            AnalysisStageTimings(
                decodeMs = decodeMs.coerceAtLeast(0L),
                sampleLoopMs = sampleLoopMs.coerceAtLeast(0L),
                fingerprintMs = fingerprintMs.coerceAtLeast(0L),
                matchMs = matchMs.coerceAtLeast(0L),
                clipMs = clipMs.coerceAtLeast(0L),
                teachMs = teachMs.coerceAtLeast(0L),
                backfillMs = backfillMs.coerceAtLeast(0L),
                totalMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L),
                chaptersMs = chaptersMs.coerceAtLeast(0L),
                chapterSource = chapterSource,
                throttledMs = throttledMs.coerceAtLeast(0L),
                throttleLevelStart = throttleLevelStart,
                throttleYields = throttleYields.coerceAtLeast(0),
                qos = qos,
            ),
        )
        return AudioPass(result, clipUnavailable = clipUnavailable, trace = trace)
    }

    /** Saves [result], then fills intro, credits, recap, and preview on the rest of the season from stored fingerprints. */
    private suspend fun saveFingerprint(
        item: DownloadItem,
        result: MediaAnalysisResult,
        onBackfillMs: (Long) -> Unit,
    ): MediaAnalysisResult {
        MediaAnalysisStore.save(result)
        val season = item.seasonNumber ?: return result
        if (item.episodeNumber == null || !result.hasStoredFingerprint()) return result
        if (!MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection)) return result
        val stored = MediaAnalysisStore.seasonResults(item.parentMetaId, season)
        val detectRecap = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection)
        noteActivity("backfill")
        InAppLogger.info("Analysis", "backfill id=${item.id} stored=${stored.size}")
        val mark = TimeSource.Monotonic.markNow()
        val filled = withContext(Dispatchers.Default) {
            backfillSeasonOnFingerprintSave(saved = result, stored = stored, detectRecap = detectRecap)
        }
        onBackfillMs(mark.elapsedNow().inWholeMilliseconds)
        var current = result
        filled.forEach { updated ->
            MediaAnalysisStore.save(updated)
            if (updated.key == result.key) current = MediaAnalysisStore.load(updated.key) ?: updated
        }
        return current
    }
}

/** Entry points for the iOS app delegate and background task. */
object MediaAnalysisBackground {
    fun appDidLaunch() {
        // A throttle or a player-open flag left from an earlier process must not hold the analyser
        // or keep its queue at the lower QoS.
        com.nuvio.app.features.downloads.PlaybackPriority.clearStaleSession()
        MediaAnalysisScheduler.kick()
        com.nuvio.app.features.downloads.DownloadsRepository.ensureLoaded()
        com.nuvio.app.features.offline.OfflineArtwork.cacheAll(
            com.nuvio.app.features.downloads.DownloadsRepository.uiState.value.completedItems,
        )
        com.nuvio.app.features.downloads.SmartDownloads.appDidLaunch()
    }

    /** BGProcessingTask: runs pending analysis; [onDone] gets true when everything finished. */
    private var backgroundJob: Job? = null

    fun didSchedule(error: String?) {
        MediaAnalysisStatus.backgroundScheduled(error)
    }

    fun runBackgroundTask(onDone: (Boolean) -> Unit) {
        MediaAnalysisScheduler.cancel()
        backgroundJob?.cancel()
        backgroundJob = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            var finished = false
            try {
                com.nuvio.app.features.downloads.SmartDownloads.retryPending()
                finished = MediaAnalysisScheduler.runPending(fromBackgroundTask = true)
            } catch (_: Throwable) {
                finished = false
            } finally {
                MediaAnalysisStatus.backgroundRan(finished)
                MediaAnalysisStatus.resultsChanged()
                onDone(finished)
                // The background task cancelled the in-app pass; without this nothing restarted it
                // and a half-done file stayed "queued" until Analyse now was tapped.
                if (!finished) MediaAnalysisScheduler.kick()
            }
        }
    }

    fun expireBackgroundTask() {
        backgroundJob?.cancel()
        backgroundJob = null
        MediaAnalysisScheduler.cancel()
        MediaAnalysisStatus.setJob(AnalysisJobState.Idle)
        MediaAnalysisStatus.resultsChanged()
        MediaAnalysisScheduler.kick()
    }

    fun requiresExternalPower(): Boolean =
        MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.OnlyWhileCharging)
}

/** Lowest season and episode first (per show, in download order); movies after episodes. */
internal val AnalysisOrder: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.parentMetaId }
        .thenBy { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.updatedAtEpochMs }
