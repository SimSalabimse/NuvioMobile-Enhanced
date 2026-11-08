package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadDiskInfo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.downloads.SMART_DOWNLOAD_MIN_FREE_BYTES
import com.nuvio.app.features.downloads.SmartDownloads
import com.nuvio.app.features.downloads.seasonStreamVideoId
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.sameCut
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.resolveSkipImdbId
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.core.serialization.LenientJson

/** Known intro (and credits, when there are any) of another episode of the season, in ms. */
internal data class SiblingTimes(
    val episode: Int,
    val intro: Pair<Long, Long>,
    val credits: Pair<Long, Long>? = null,
    val durationMs: Long = 0L,
)

/**
 * A downloaded episode with no analysed sibling to match against: take the nearest episode of the
 * season whose intro is known (your own mark first, then TheIntroDB/IntroDB), fingerprint just
 * that window of its stream, find the same audio in this episode and carry the times over. The
 * clip is decoded in memory and dropped when the call returns, so nothing is left to delete.
 * The episode is then stored with its own fingerprints, the reference for later episodes.
 */
/** A clip was read, or there was nothing to fetch. [Unavailable] is tried again. */
internal enum class SiblingClipAttempt { Finished, Unavailable }

internal data class SiblingClipOutcome(val result: MediaAnalysisResult, val attempt: SiblingClipAttempt)

internal object SiblingClipReference {
    private const val PAD_MS = 5_000L
    private const val MIN_MATCH_MS = 8_000L
    private const val MAX_ONLINE_LOOKUPS = 4
    private val json = LenientJson
    private val clipCache = ReferenceFingerprintCache()

    suspend fun apply(
        item: DownloadItem,
        result: MediaAnalysisResult,
        onStatus: (String) -> Unit = {},
    ): SiblingClipOutcome {
        fun finished(value: MediaAnalysisResult = result) = SiblingClipOutcome(value, SiblingClipAttempt.Finished)
        fun unavailable(value: MediaAnalysisResult = result) = SiblingClipOutcome(value, SiblingClipAttempt.Unavailable)
        val season = item.seasonNumber ?: return finished()
        val episode = item.episodeNumber ?: return finished()
        val free = DownloadDiskInfo.volumeSpace()?.availableBytes
        if (free != null && free < SMART_DOWNLOAD_MIN_FREE_BYTES) return unavailable()
        val meta = MetaDetailsRepository.fetch(item.parentMetaType, item.parentMetaId) ?: return unavailable()
        val episodes = meta.videos.filter { it.season == season }.mapNotNull { it.episode }.distinct()
        var resolvedImdb: String? = null
        var imdbResolved = false
        val localFiles = completedLocalSiblingFiles(item)
        val localEpisodes = localFiles.map { it.episode }
        val remoteEpisodes = episodes.filter { it != episode && it !in localEpisodes }
        val plan = planReference(episode, localEpisodes, remoteEpisodes)
        if (plan.choice == ReferenceChoice.None) {
            InAppLogger.info("Analysis", "reference skip id=${item.id} reason=${plan.reason}")
            return finished()
        }
        suspend fun timesFor(candidate: Int): SiblingTimes? {
            ownMarks(item.parentMetaId, season).firstOrNull { it.episode == candidate }?.let { return it }
            if (!imdbResolved) {
                imdbResolved = true
                resolvedImdb = resolveSkipImdbId(item.parentMetaId, null, "series")
            }
            return onlineTimes(
                SkipIntroRepository.getSkipIntervals(
                    resolvedImdb,
                    season,
                    candidate,
                    requireSkipIntroEnabled = false,
                    durationMs = result.durationMs,
                ),
                candidate,
            )
        }
        var chosenTimes: SiblingTimes? = null
        var chosenLocal = false
        for (candidate in nearestFirst(episode, localEpisodes)) {
            timesFor(candidate)?.let {
                chosenTimes = it
                chosenLocal = true
                return@let
            }
            if (chosenTimes != null) break
        }
        if (chosenTimes == null) {
            for (candidate in nearestFirst(episode, remoteEpisodes).take(MAX_ONLINE_LOOKUPS)) {
                timesFor(candidate)?.let { chosenTimes = it }
                if (chosenTimes != null) break
            }
        }
        val reference = chosenTimes
        if (reference == null) {
            InAppLogger.info("Analysis", "reference skip id=${item.id} reason=no-sibling-with-known-times")
            return finished()
        }
        val localFile = localFiles.firstOrNull { it.episode == reference.episode }
        val (url, headers, identity) = if (chosenLocal && localFile != null) {
            Triple(localFile.uri, emptyMap(), referenceIdentity(localFile.fileName, resolvedImdb.orEmpty(), season, reference.episode))
        } else {
            val video = meta.videos.firstOrNull { it.season == season && it.episode == reference.episode } ?: return finished()
            val stream = streamFor(meta, video, item) ?: return unavailable()
            Triple(
                stream.first,
                stream.second,
                referenceIdentity(null, resolvedImdb ?: meta.imdbId.orEmpty(), season, reference.episode),
            )
        }
        val operation = if (chosenLocal) "read" else "fetch"
        val step = result.fingerprintStepMs.toLong().coerceAtLeast(1L)

        var updated = result
        var missing = false
        var read = false
        if (result.introStartMs == null) {
            if (referenceSampleWindow(reference.intro.first, reference.intro.second, PAD_MS).complete) {
                onStatus(referenceActivityToken(operation, windowSeconds(reference.intro), season, reference.episode))
            }
            when (val clip = readClip(url, headers, identity, decodeFingerprint(result.headFingerprint), 0L, step, reference.intro, result.durationMs, reference.durationMs)) {
                ClipRead.Missing -> missing = true
                ClipRead.Skipped, ClipRead.Partial -> Unit
                is ClipRead.Read -> {
                    read = true
                    clip.range?.let { (start, end) ->
                        updated = updated.copy(introStartMs = start, introEndMs = end, foundUsing = updated.foundUsing + ("intro" to reference.episode))
                    }
                }
            }
        }
        val credits = reference.credits
        if (credits != null && result.creditsStartMs == null) {
            if (referenceSampleWindow(credits.first, credits.second, PAD_MS).complete) {
                onStatus(referenceActivityToken(operation, windowSeconds(credits), season, reference.episode))
            }
            when (val clip = readClip(url, headers, identity, decodeFingerprint(result.tailFingerprint), result.tailStartMs, step, credits, result.durationMs, reference.durationMs)) {
                ClipRead.Missing -> missing = true
                ClipRead.Skipped, ClipRead.Partial -> Unit
                is ClipRead.Read -> {
                    read = true
                    clip.range?.let { (start, end) ->
                        updated = updated.copy(creditsStartMs = start, creditsEndMs = end, foundUsing = updated.foundUsing + ("outro" to reference.episode))
                    }
                }
            }
        }
        if (missing && !read) return unavailable(updated)
        InAppLogger.info(
            "Analysis",
            "sibling clip e${reference.episode} for ${item.id}: intro=${updated.introStartMs}-${updated.introEndMs} " +
                "credits=${updated.creditsStartMs}-${updated.creditsEndMs}",
        )
        return finished(updated)
    }

    private sealed interface ClipRead {
        data object Missing : ClipRead
        data object Skipped : ClipRead
        data class Read(val range: Pair<Long, Long>?) : ClipRead
        data object Partial : ClipRead
    }

    private fun windowSeconds(segment: Pair<Long, Long>): Int {
        val window = referenceSampleWindow(segment.first, segment.second, PAD_MS)
        val shown = if (window.complete) window.endMs - window.startMs else window.requestedMs
        return (shown / 1_000L).toInt().coerceAtLeast(1)
    }

    private suspend fun readClip(
        url: String,
        headers: Map<String, String>,
        identity: String,
        frames: IntArray,
        framesOffsetMs: Long,
        step: Long,
        segment: Pair<Long, Long>,
        targetDurationMs: Long,
        referenceDurationMs: Long,
    ): ClipRead {
        if (frames.isEmpty()) return ClipRead.Skipped
        val window = referenceSampleWindow(segment.first, segment.second, PAD_MS)
        if (!window.complete) {
            InAppLogger.info(
                "Analysis",
                "reference partial reason=requested-window-longer-than-budget requestedMs=${window.requestedMs}",
            )
            return ClipRead.Partial
        }
        val key = ReferenceFingerprintKey(identity, window.startMs, window.endMs, REFERENCE_FINGERPRINT_ALGORITHM)
        val cached = clipCache.lookup(key)
        val text = if (cached != null) {
            cached.first
        } else {
            clipCache.beginDecode(identity)
            val decoded = MediaAnalyzerPlatform.fingerprintClip(url, headers, window.startMs, window.endMs)
            if (decoded == null) {
                clipCache.endDecode(identity)
                return ClipRead.Missing
            }
            clipCache.store(key, decoded)
            decoded
        }
        val clip = runCatching { json.decodeFromString(RawClipFingerprint.serializer(), text) }.getOrNull()
            ?: return ClipRead.Missing
        if (!clip.windowComplete) return ClipRead.Partial
        if (reportedClipExceedsBudget(clip.decodedFrames, clip.decodedSamples, window.endMs - window.startMs)) {
            return ClipRead.Missing
        }
        if (clip.stepMs.toLong() != step) return ClipRead.Skipped
        return ClipRead.Read(
            locate(
                frames,
                framesOffsetMs,
                decodeFingerprint(clip.fingerprint),
                clip.startMs,
                step,
                segment,
                targetDurationMs,
                referenceDurationMs,
            ),
        )
    }

    private data class LocalSiblingFile(val episode: Int, val fileName: String, val uri: String)

    /** Completed files of other episodes. The episode being analysed is not included. */
    private fun completedLocalSiblingFiles(item: DownloadItem): List<LocalSiblingFile> =
        DownloadsRepository.uiState.value.items.mapNotNull { other ->
            if (other.parentMetaId != item.parentMetaId || other.seasonNumber != item.seasonNumber) return@mapNotNull null
            val otherEpisode = other.episodeNumber ?: return@mapNotNull null
            if (otherEpisode == item.episodeNumber || other.status != DownloadStatus.Completed) return@mapNotNull null
            val uri = DownloadsRepository.playableLocalFileUri(other)?.takeIf { it.startsWith("file:") } ?: return@mapNotNull null
            LocalSiblingFile(otherEpisode, other.fileName.orEmpty(), uri)
        }

    /** Your marks on other episodes of the season (also those never downloaded or analysed). */
    private fun ownMarks(parentMetaId: String, season: Int): List<SiblingTimes> =
        MediaAnalysisStore.seasonResults(parentMetaId, season).mapNotNull { stored ->
            val episode = Regex("e(\\d+)$").find(stored.key)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val intro = stored.userSegments["intro"]?.takeIf { it.endMs > it.startMs && it.canTeach } ?: return@mapNotNull null
            val outro = stored.userSegments["outro"]?.takeIf { it.endMs > it.startMs && it.canTeach }
            SiblingTimes(episode, intro.startMs to intro.endMs, outro?.let { it.startMs to it.endMs }, stored.durationMs)
        }

    private suspend fun streamFor(meta: MetaDetails, video: MetaVideo, item: DownloadItem): Pair<String, Map<String, String>>? {
        val videoId = seasonStreamVideoId(meta.id, video)
        val maxAgeMs = PlayerSettingsRepository.uiState.value.streamReuseLastLinkCacheHours * 3_600_000L
        val cacheKey = StreamLinkCacheRepository.contentKey(meta.type, videoId, meta.id, video.season, video.episode)
        StreamLinkCacheRepository.getValid(cacheKey, maxAgeMs)?.takeIf { it.url.isNotBlank() }?.let {
            return it.url to it.requestHeaders
        }
        val picked = SmartDownloads.pickStream(meta, video, item) ?: return null
        val stream = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(picked)) {
            (DirectDebridPlaybackResolver.resolveToPlayableStream(picked, video.season, video.episode) as? DirectDebridPlayableResult.Success)
                ?.stream ?: return null
        } else {
            picked
        }
        val url = stream.playableDirectUrl?.takeIf { it.isNotBlank() } ?: return null
        return url to stream.behaviorHints.proxyHeaders?.request.orEmpty()
    }

    /** Episodes nearest to [target] first; the earlier one wins a tie. */
    fun nearestFirst(target: Int, episodes: List<Int>): List<Int> =
        episodes.filter { it != target }.distinct().sortedWith(compareBy({ kotlin.math.abs(it - target) }, { it }))

    /**
     * Your nearest marked episode, else the nearest of the next [MAX_ONLINE_LOOKUPS] episodes
     * with online times. [lookup] is called at most that many times.
     */
    suspend fun pickReference(
        target: Int,
        own: List<SiblingTimes>,
        episodes: List<Int>,
        lookup: suspend (Int) -> SiblingTimes?,
    ): SiblingTimes? {
        val marked = own.filter { it.episode != target }.associateBy { it.episode }
        nearestFirst(target, marked.keys.toList()).firstOrNull()?.let { return marked.getValue(it) }
        for (episode in nearestFirst(target, episodes).take(MAX_ONLINE_LOOKUPS)) {
            lookup(episode)?.let { return it }
        }
        return null
    }

    /** TheIntroDB/IntroDB times of one episode; null without an intro. */
    fun onlineTimes(intervals: List<SkipInterval>, episode: Int): SiblingTimes? {
        val trusted = intervals.filter { it.provider == TheIntroDb.PROVIDER || it.provider == "introdb" }
        fun range(types: Set<String>) = trusted.firstOrNull { it.type.lowercase() in types && it.endTime > it.startTime }
            ?.let { (it.startTime * 1000).toLong() to (it.endTime * 1000).toLong() }
        val intro = range(setOf("intro", "op", "mixed-op")) ?: return null
        return SiblingTimes(episode, intro, range(setOf("outro", "ed", "mixed-ed", "credits", "ending")))
    }

    /**
     * Shared audio of [segment] inside this episode. The returned range is that shared run in
     * [frames], not a copy of the sibling window. Under half the sibling window, or file lengths
     * more than 30 seconds apart, transfers nothing. A length of 0 does not prove a different cut.
     */
    fun locate(
        frames: IntArray,
        framesOffsetMs: Long,
        clip: IntArray,
        clipStartMs: Long,
        step: Long,
        segment: Pair<Long, Long>,
        targetDurationMs: Long = 0L,
        referenceDurationMs: Long = 0L,
    ): Pair<Long, Long>? {
        if (targetDurationMs > 0L && referenceDurationMs > 0L && !sameCut(referenceDurationMs, targetDurationMs)) {
            return null
        }
        val windowMs = (segment.second - segment.first).coerceAtLeast(0L)
        val minMs = maxOf(MIN_MATCH_MS, windowMs / 2)
        val match = FingerprintMatcher.longestSharedSegment(frames, clip, minFrames = (minMs / step).toInt().coerceAtLeast(1))
            ?: return null
        val windowFrames = (windowMs / step).toInt()
        if (windowFrames > 0 && match.length * 2 < windowFrames) return null
        val start = framesOffsetMs + match.startA * step
        val end = framesOffsetMs + match.endA * step
        return if (end > start) start to end else null
    }
}
