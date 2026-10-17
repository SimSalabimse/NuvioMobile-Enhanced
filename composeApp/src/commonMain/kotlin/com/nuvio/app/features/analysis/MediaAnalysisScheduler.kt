package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.InAppPlaybackKeepAlive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
}

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
    private const val RETRY_DELAY_MS = 2 * 60_000L
    private const val MAX_ATTEMPTS = 3
    private const val MAX_SIBLINGS = 4
    private const val THUMBNAIL_TARGET_COUNT = 400L
    private const val THUMBNAIL_MIN_INTERVAL_MS = 5_000L
    private const val THUMBNAIL_MAX_INTERVAL_MS = 20_000L
    private const val THUMBNAIL_TILE_WIDTH = 160

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    private val anyFeatureEnabled: Boolean
        get() = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.SubtitleSync) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.Loudness) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.ScrubPreviews) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection)

    /** Starts a pass over finished downloads unless one is already running. */
    fun kick(startDelayMs: Long = START_DELAY_MS) {
        if (!MediaAnalyzerPlatform.supported) return
        if (job?.isActive == true) return
        job = scope.launch {
            MediaAnalysisStatus.setJob(AnalysisJobState.Starting)
            try {
                delay(startDelayMs)
                runPending(fromBackgroundTask = false)
            } finally {
                if (MediaAnalysisStatus.job.value !is AnalysisJobState.WaitingForCharger) {
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
        val runningId = (MediaAnalysisStatus.job.value as? AnalysisJobState.Running)?.itemId
        if (runningId != downloadId && currentItem?.id != downloadId) return
        if (priorityItemId == downloadId) priorityItemId = null
        cancel()
        kick()
    }

    /** True when every pending file was analysed (false: stopped, cancelled or not allowed). */
    suspend fun runPending(fromBackgroundTask: Boolean): Boolean {
        if (!MediaAnalyzerPlatform.supported || !anyFeatureEnabled) return true
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
                com.nuvio.app.features.downloads.PlaybackPriority.Level.Strained
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
            val ok = try {
                analyzeOne(item)
            } finally {
                MediaAnalysisStatus.setJob(AnalysisJobState.Idle)
                MediaAnalysisStatus.resultsChanged()
            }
            if (!scopeActive()) return false
            if (ok) MediaAnalysisStatus.clearFailure(item.id)
            if (ok) retries -= item.id
            if (!ok) {
                // Try again later instead of skipping the file for the rest of the session: it
                // used to stay "partly analysed, queued for more" until Analyse now was tapped.
                // After a few tries it is left alone and shown as failed, with the reason.
                val attempt = (retries[item.id]?.first ?: 0) + 1
                if (attempt >= MAX_ATTEMPTS) {
                    retries -= item.id
                    failedThisSession += item.id
                    if (MediaAnalysisStatus.statusFor(item) !is ItemAnalysisStatus.Failed) {
                        MediaAnalysisStatus.recordFailure(item, MediaAnalyzerPlatform.lastError() ?: "Analysis stopped")
                    }
                } else {
                    retries[item.id] = attempt to (com.nuvio.app.features.streams.epochMs() + RETRY_DELAY_MS * attempt)
                }
            }
        }
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

    private fun stage(stage: AnalysisStage) {
        val item = currentItem ?: return
        val title = buildString {
            append(item.title)
            if (item.seasonNumber != null && item.episodeNumber != null) append(" S${item.seasonNumber}E${item.episodeNumber}")
        }
        MediaAnalysisStatus.setJob(AnalysisJobState.Running(item.id, title, stage, if (stage == AnalysisStage.Audio || stage == AnalysisStage.Previews) 0.0 else -1.0, currentInBackground))
    }

    /** Runs [block] while copying the platform analyzer's progress into the status every half second. */
    private suspend fun <T> withProgress(block: suspend () -> T): T = kotlinx.coroutines.coroutineScope {
        val poller = launch {
            while (isActive) {
                delay(500)
                MediaAnalysisStatus.updateProgress(MediaAnalyzerPlatform.progress())
            }
        }
        try {
            block()
        } finally {
            poller.cancel()
        }
    }

    private suspend fun scopeActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun nextPending(): DownloadItem? = DownloadsRepository.uiState.value.items
        .filter { it.status == DownloadStatus.Completed && it.id !in failedThisSession && !isBackingOff(it.id) }
        .sortedWith(compareBy<DownloadItem> { if (it.id == priorityItemId) 0 else 1 }.thenBy { it.updatedAtEpochMs })
        .firstOrNull { item ->
            val existing = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item))
            existing == null || existing.fileName != item.fileName || needsDerived(existing)
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

    private suspend fun analyzeOne(item: DownloadItem): Boolean {
        val uri = DownloadsRepository.playableLocalFileUri(item) ?: run {
            MediaAnalysisStatus.recordFailure(item, "The file is not on this device")
            return false
        }
        if (!uri.startsWith("file:")) {
            MediaAnalysisStatus.recordFailure(item, "The file is not on this device")
            return false
        }
        val key = MediaAnalysisStore.keyFor(item)
        val base = MediaAnalysisStore.load(key)?.takeIf { it.fileName == item.fileName }
            ?: audioAnalysis(item, uri)
            ?: return false
        return derivedAnalysis(item, uri, base)
    }

    /** Recap and scrub-preview thumbnails, computed from a stored analysis and the file. */
    private suspend fun derivedAnalysis(item: DownloadItem, uri: String, base: MediaAnalysisResult): Boolean {
        var result = base
        val season = item.seasonNumber
        if (season != null && item.episodeNumber != null &&
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.RecapDetection)
        ) {
            val siblings = MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, result.key)
            stage(AnalysisStage.Recap)
            val recap = withContext(Dispatchers.Default) { RecapDetector.detect(result, siblings) }
            result = result.copy(recapStartMs = recap?.first, recapEndMs = recap?.second)
        }
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
        if (!scopeActive()) return false
        MediaAnalysisStore.save(result.copy(derivedVersion = MEDIA_ANALYSIS_DERIVED_VERSION))
        return thumbsOk
    }

    private suspend fun audioAnalysis(item: DownloadItem, uri: String): MediaAnalysisResult? {
        InAppLogger.info("Analysis", "analyse start id=${item.id} file=${item.fileName}")
        stage(AnalysisStage.Audio)
        val text = withProgress { MediaAnalyzerPlatform.analyze(uri) } ?: run {
            InAppLogger.warn("Analysis", "analyse failed id=${item.id}")
            if (scopeActive()) MediaAnalysisStatus.recordFailure(item, MediaAnalyzerPlatform.lastError() ?: "Could not analyse the audio")
            return null
        }
        val raw = runCatching { json.decodeFromString(RawMediaAnalysis.serializer(), text) }.getOrNull() ?: run {
            MediaAnalysisStatus.recordFailure(item, "The analyser returned unreadable data")
            return null
        }
        val key = MediaAnalysisStore.keyFor(item)
        var result = MediaAnalysisResult(
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
            userSegments = MediaAnalysisStore.load(key)?.userSegments.orEmpty(),
        )
        val season = item.seasonNumber
        if (season != null && item.episodeNumber != null &&
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection)
        ) {
            val siblings = MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, key)
                .sortedByDescending { it.analyzedAtMs }
                .take(MAX_SIBLINGS)
            if (siblings.isNotEmpty()) {
                stage(AnalysisStage.Matching)
                val detected = withContext(Dispatchers.Default) { SeasonSegmentDetector.detect(result, siblings) }
                result = result.copy(
                    introStartMs = detected.introStartMs,
                    introEndMs = detected.introEndMs,
                    creditsStartMs = detected.creditsStartMs,
                    creditsEndMs = detected.creditsEndMs,
                )
                // Earlier episodes that had nobody to match against get their segments now.
                siblings.filter { it.introEndMs == null || it.creditsStartMs == null }.forEach { sibling ->
                    val back = withContext(Dispatchers.Default) { SeasonSegmentDetector.detect(sibling, listOf(result)) }
                    MediaAnalysisStore.save(
                        sibling.copy(
                            introStartMs = sibling.introStartMs ?: back.introStartMs,
                            introEndMs = sibling.introEndMs ?: back.introEndMs,
                            creditsStartMs = sibling.creditsStartMs ?: back.creditsStartMs,
                            creditsEndMs = sibling.creditsEndMs ?: back.creditsEndMs,
                            // A newly found intro lets the recap search run again.
                            derivedVersion = if (sibling.introEndMs == null && back.introEndMs != null) 0 else sibling.derivedVersion,
                        ),
                    )
                }
            }
            // Episodes the user marked are trusted references: they beat the pairwise guess.
            val references = MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, key)
                .filter { it.userSegments.containsKey("intro") || it.userSegments.containsKey("outro") }
            if (references.isNotEmpty()) {
                stage(AnalysisStage.Matching)
                result = withContext(Dispatchers.Default) { SkipLearning.learnFromReferences(result, references) }
            }
        }
        MediaAnalysisStore.save(result)
        // A mark made before this file was analysed now has fingerprints: teach the season.
        if (season != null && item.episodeNumber != null &&
            (result.userSegments.containsKey("intro") || result.userSegments.containsKey("outro"))
        ) {
            SkipLearning.teachSeason(item.parentMetaId, season, key)
        }
        InAppLogger.info(
            "Analysis",
            "analyse done id=${item.id} durationMs=${raw.durationMs} lufs=${raw.integratedLufs} " +
                "intro=${result.introStartMs}-${result.introEndMs} credits=${result.creditsStartMs}-${result.creditsEndMs}",
        )
        return result
    }
}

/** Entry points for the iOS app delegate and background task. */
object MediaAnalysisBackground {
    fun appDidLaunch() {
        // A throttle left from a player in an earlier process must not hold the analyser.
        setAnalysisThrottleLevel(0)
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
