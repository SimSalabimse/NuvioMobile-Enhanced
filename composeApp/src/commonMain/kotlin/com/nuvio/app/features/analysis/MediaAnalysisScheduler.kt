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
}

/**
 * Runs the on-device analysis of finished downloads one file at a time: after a download
 * finishes, at app launch, and from the iOS background processing task. Waits while something
 * is playing in the app, honours "only while charging", and can be cancelled at any point
 * ([cancel], or the background task expiring).
 */
internal object MediaAnalysisScheduler {
    private const val START_DELAY_MS = 20_000L
    private const val PLAYBACK_POLL_MS = 15_000L
    private const val MAX_SIBLINGS = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null

    private val anyFeatureEnabled: Boolean
        get() = MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.SubtitleSync) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection) ||
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.Loudness)

    /** Starts a pass over finished downloads unless one is already running. */
    fun kick(startDelayMs: Long = START_DELAY_MS) {
        if (!MediaAnalyzerPlatform.supported) return
        if (job?.isActive == true) return
        job = scope.launch {
            delay(startDelayMs)
            runPending(fromBackgroundTask = false)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        MediaAnalyzerPlatform.cancel()
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
                return false
            }
            if (!fromBackgroundTask && InAppPlaybackKeepAlive.isPlaying()) {
                delay(PLAYBACK_POLL_MS)
                continue
            }
            val item = nextPending() ?: return true
            val ok = analyzeOne(item)
            if (!scopeActive()) return false
            if (!ok) {
                // Remember the failure for this session so one bad file does not loop.
                failedThisSession += item.id
            }
        }
    }

    private val failedThisSession = mutableSetOf<String>()

    private suspend fun scopeActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private fun nextPending(): DownloadItem? = DownloadsRepository.uiState.value.items
        .filter { it.status == DownloadStatus.Completed && it.id !in failedThisSession }
        .sortedBy { it.updatedAtEpochMs }
        .firstOrNull { item ->
            val existing = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item))
            existing == null || existing.fileName != item.fileName
        }

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
        val uri = DownloadsRepository.playableLocalFileUri(item) ?: return false
        if (!uri.startsWith("file:")) return false
        InAppLogger.info("Analysis", "analyse start id=${item.id} file=${item.fileName}")
        val text = MediaAnalyzerPlatform.analyze(uri) ?: run {
            InAppLogger.warn("Analysis", "analyse failed id=${item.id}")
            return false
        }
        val raw = runCatching { json.decodeFromString(RawMediaAnalysis.serializer(), text) }.getOrNull() ?: return false
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
        )
        val season = item.seasonNumber
        if (season != null && item.episodeNumber != null &&
            MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.IntroDetection)
        ) {
            val siblings = MediaAnalysisStore.seasonSiblings(item.parentMetaId, season, key)
                .sortedByDescending { it.analyzedAtMs }
                .take(MAX_SIBLINGS)
            if (siblings.isNotEmpty()) {
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
                        ),
                    )
                }
            }
        }
        MediaAnalysisStore.save(result)
        InAppLogger.info(
            "Analysis",
            "analyse done id=${item.id} durationMs=${raw.durationMs} lufs=${raw.integratedLufs} " +
                "intro=${result.introStartMs}-${result.introEndMs} credits=${result.creditsStartMs}-${result.creditsEndMs}",
        )
        return true
    }
}

/** Entry points for the iOS app delegate and background task. */
object MediaAnalysisBackground {
    fun appDidLaunch() {
        MediaAnalysisScheduler.kick()
        com.nuvio.app.features.downloads.SmartDownloads.appDidLaunch()
    }

    /** BGProcessingTask: runs pending analysis; [onDone] gets true when everything finished. */
    private var backgroundJob: Job? = null

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
                onDone(finished)
            }
        }
    }

    fun expireBackgroundTask() {
        backgroundJob?.cancel()
        backgroundJob = null
        MediaAnalysisScheduler.cancel()
    }

    fun requiresExternalPower(): Boolean =
        MediaAnalysisSettings.isEnabled(MediaAnalysisSettings.Flag.OnlyWhileCharging)
}
