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
internal enum class SiblingClipAttempt { Finished, Unavailable, Preempted }

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
        yieldToLocal: () -> Boolean = { false },
    ): SiblingClipOutcome {
        fun finished(value: MediaAnalysisResult = result) = SiblingClipOutcome(value, SiblingClipAttempt.Finished)
        fun unavailable(value: MediaAnalysisResult = result) = SiblingClipOutcome(value, SiblingClipAttempt.Unavailable)
        val season = item.seasonNumber ?: return finished()
        val episode = item.episodeNumber ?: return finished()
        val free = DownloadDiskInfo.volumeSpace()?.availableBytes
        if (free != null && free < SMART_DOWNLOAD_MIN_FREE_BYTES) return unavailable()
        val localFiles = completedLocalSiblingFiles(item)
        val localEpisodes = localFiles.map { it.episode }
        val markedLocal = ownMarks(item.parentMetaId, season).filter { it.episode in localEpisodes }
        val localEpisode = nearestFirst(episode, markedLocal.map { it.episode }).firstOrNull()
        if (localEpisode != null) {
            val reference = markedLocal.first { it.episode == localEpisode }
            val localFile = localFiles.first { it.episode == localEpisode }
            return readKnownReference(
                item = item,
                result = result,
                reference = reference,
                url = localFile.uri,
                headers = emptyMap(),
                identity = referenceIdentity(localFile.fileName, "", season, reference.episode),
                operation = "read",
                onStatus = onStatus,
                yieldToLocal = yieldToLocal,
            )
        }
        val meta = MetaDetailsRepository.fetch(item.parentMetaType, item.parentMetaId) ?: return unavailable()
        val episodes = meta.videos.filter { it.season == season }.mapNotNull { it.episode }.distinct()
        var resolvedImdb: String? = null
        var imdbResolved = false
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
        return readKnownReference(
            item = item,
            result = result,
            reference = reference,
            url = url,
            headers = headers,
            identity = identity,
            operation = if (chosenLocal) "read" else "fetch",
            onStatus = onStatus,
            yieldToLocal = yieldToLocal,
        )
    }

    private suspend fun readKnownReference(
        item: DownloadItem,
        result: MediaAnalysisResult,
        reference: SiblingTimes,
        url: String,
        headers: Map<String, String>,
        identity: String,
        operation: String,
        onStatus: (String) -> Unit,
        yieldToLocal: () -> Boolean,
    ): SiblingClipOutcome {
        fun finished(value: MediaAnalysisResult = result) = SiblingClipOutcome(value, SiblingClipAttempt.Finished)
        fun unavailable(value: MediaAnalysisResult = result) = SiblingClipOutcome(value, SiblingClipAttempt.Unavailable)
        val season = item.seasonNumber ?: return finished()
        val step = result.fingerprintStepMs.toLong().coerceAtLeast(1L)
        var updated = result
        var missing = false
        var preempted = false
        if (result.introStartMs == null) {
            when (
                val aligned = alignReferenceSegment(
                    segmentStartMs = reference.intro.first,
                    segmentEndMs = reference.intro.second,
                    padMs = PAD_MS,
                    frames = decodeFingerprint(result.headFingerprint),
                    framesOffsetMs = 0L,
                    stepMs = step,
                    targetDurationMs = result.durationMs,
                    referenceDurationMs = reference.durationMs,
                    yieldToLocal = yieldToLocal,
                    onSlice = { seconds ->
                        onStatus(referenceActivityToken(operation, seconds, season, reference.episode))
                    },
                    decode = { start, end -> decodeWindow(url, headers, identity, start, end) },
                )
            ) {
                is ReferenceAlignment.Matched -> {
                    updated = updated.copy(
                        introStartMs = aligned.range.first,
                        introEndMs = aligned.range.second,
                        foundUsing = updated.foundUsing + ("intro" to reference.episode),
                    )
                }
                is ReferenceAlignment.Partial -> {
                    InAppLogger.info("Analysis", "reference partial reason=${aligned.reason} segment=intro")
                    missing = true
                }
                ReferenceAlignment.Preempted -> preempted = true
                ReferenceAlignment.Unreadable -> missing = true
            }
        }
        val credits = reference.credits
        if (!preempted && credits != null && result.creditsStartMs == null) {
            when (
                val aligned = alignReferenceSegment(
                    segmentStartMs = credits.first,
                    segmentEndMs = credits.second,
                    padMs = PAD_MS,
                    frames = decodeFingerprint(result.tailFingerprint),
                    framesOffsetMs = result.tailStartMs,
                    stepMs = step,
                    targetDurationMs = result.durationMs,
                    referenceDurationMs = reference.durationMs,
                    yieldToLocal = yieldToLocal,
                    onSlice = { seconds ->
                        onStatus(referenceActivityToken(operation, seconds, season, reference.episode))
                    },
                    decode = { start, end -> decodeWindow(url, headers, identity, start, end) },
                )
            ) {
                is ReferenceAlignment.Matched -> {
                    updated = updated.copy(
                        creditsStartMs = aligned.range.first,
                        creditsEndMs = aligned.range.second,
                        foundUsing = updated.foundUsing + ("outro" to reference.episode),
                    )
                }
                is ReferenceAlignment.Partial -> {
                    InAppLogger.info("Analysis", "reference partial reason=${aligned.reason} segment=credits")
                    missing = true
                }
                ReferenceAlignment.Preempted -> preempted = true
                ReferenceAlignment.Unreadable -> missing = true
            }
        }
        if (preempted) return SiblingClipOutcome(result, SiblingClipAttempt.Preempted)
        if (missing) return unavailable(result)
        InAppLogger.info(
            "Analysis",
            "sibling clip e${reference.episode} for ${item.id}: intro=${updated.introStartMs}-${updated.introEndMs} " +
                "credits=${updated.creditsStartMs}-${updated.creditsEndMs}",
        )
        return finished(updated)
    }

    private suspend fun decodeWindow(
        url: String,
        headers: Map<String, String>,
        identity: String,
        startMs: Long,
        endMs: Long,
    ): RawClipFingerprint? {
        val key = ReferenceFingerprintKey(identity, startMs, endMs, REFERENCE_FINGERPRINT_ALGORITHM)
        val text = clipCache.lookup(key)?.first ?: run {
            clipCache.beginDecode(identity)
            val hook = MediaAnalysisScheduler.fingerprintClipForTest
            val decoded = if (hook != null) {
                hook(url, startMs, endMs)
            } else {
                MediaAnalyzerPlatform.fingerprintClip(url, headers, startMs, endMs)
            }
            if (decoded == null) {
                clipCache.endDecode(identity)
                return null
            }
            clipCache.store(key, decoded)
            decoded
        }
        return runCatching { json.decodeFromString(RawClipFingerprint.serializer(), text) }.getOrNull()
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

internal sealed interface ReferenceAlignment {
    data class Matched(val range: Pair<Long, Long>) : ReferenceAlignment
    data class Partial(val reason: String) : ReferenceAlignment
    data object Preempted : ReferenceAlignment
    data object Unreadable : ReferenceAlignment
}

/**
 * Fingerprints bounded slices of a known segment and maps a verified offset back onto the full segment.
 * The decoder is never asked for more than 50 seconds. A slice is not stored as the whole segment.
 */
internal suspend fun alignReferenceSegment(
    segmentStartMs: Long,
    segmentEndMs: Long,
    padMs: Long,
    frames: IntArray,
    framesOffsetMs: Long,
    stepMs: Long,
    targetDurationMs: Long,
    referenceDurationMs: Long,
    yieldToLocal: () -> Boolean = { false },
    onSlice: (seconds: Int) -> Unit = {},
    decode: suspend (decodeStartMs: Long, decodeEndMs: Long) -> RawClipFingerprint?,
): ReferenceAlignment {
    if (frames.isEmpty() || stepMs <= 0L) return ReferenceAlignment.Unreadable
    val slices = referenceDecodeSlices(segmentStartMs, segmentEndMs, padMs)
    if (slices.isEmpty()) return ReferenceAlignment.Partial("no-slice")
    val matches = mutableListOf<Pair<ReferenceSlice, Pair<Long, Long>>>()
    for (slice in slices) {
        if (yieldToLocal()) return ReferenceAlignment.Preempted
        val windowMs = slice.decodeEndMs - slice.decodeStartMs
        if (windowMs <= 0L || windowMs > REFERENCE_MAX_WINDOW_MS) {
            return ReferenceAlignment.Partial("slice-over-budget")
        }
        onSlice((windowMs / 1_000L).toInt().coerceAtLeast(1))
        val clip = decode(slice.decodeStartMs, slice.decodeEndMs)
            ?: return if (yieldToLocal()) ReferenceAlignment.Preempted else ReferenceAlignment.Unreadable
        if (!clipCoversRequestedWindow(clip.windowComplete, clip.stopReason)) {
            return ReferenceAlignment.Partial(clip.stopReason ?: "incomplete-window")
        }
        if (reportedClipExceedsBudget(clip.decodedFrames, clip.decodedSamples, windowMs)) {
            return ReferenceAlignment.Partial("over-budget")
        }
        if (clip.stepMs.toLong() != stepMs) return ReferenceAlignment.Partial("step-mismatch")
        val range = SiblingClipReference.locate(
            frames,
            framesOffsetMs,
            decodeFingerprint(clip.fingerprint),
            clip.startMs,
            stepMs,
            slice.contentStartMs to slice.contentEndMs,
            targetDurationMs,
            referenceDurationMs,
        ) ?: continue
        matches += slice to range
    }
    val bounds = verifiedSegmentBounds(segmentStartMs, segmentEndMs, slices, matches)
        ?: return ReferenceAlignment.Partial("unverified-slice")
    return ReferenceAlignment.Matched(bounds)
}
