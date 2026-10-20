package com.nuvio.app.features.analysis

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.downloads.DownloadDiskInfo
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadNetworkGuard
import com.nuvio.app.features.downloads.SMART_DOWNLOAD_MIN_FREE_BYTES
import com.nuvio.app.features.downloads.SmartDownloadSettings
import com.nuvio.app.features.downloads.SmartDownloads
import com.nuvio.app.features.downloads.seasonStreamVideoId
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.SkipIntroRepository
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.resolveSkipImdbId
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import kotlinx.serialization.json.Json

/** Known intro (and credits, when there are any) of another episode of the season, in ms. */
internal data class SiblingTimes(
    val episode: Int,
    val intro: Pair<Long, Long>,
    val credits: Pair<Long, Long>? = null,
)

/**
 * A downloaded episode with no analysed sibling to match against: take the nearest episode of the
 * season whose intro is known (your own mark first, then TheIntroDB/IntroDB), fingerprint just
 * that window of its stream, find the same audio in this episode and carry the times over. The
 * clip is decoded in memory and dropped when the call returns, so nothing is left to delete.
 * The episode is then stored with its own fingerprints, the reference for later episodes.
 */
internal object SiblingClipReference {
    private const val PAD_MS = 5_000L
    private const val MIN_MATCH_MS = 8_000L
    private const val MAX_ONLINE_LOOKUPS = 4
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun apply(item: DownloadItem, result: MediaAnalysisResult): MediaAnalysisResult {
        val season = item.seasonNumber ?: return result
        val episode = item.episodeNumber ?: return result
        if (SmartDownloadSettings.snapshot().onlyOnWifi && !DownloadNetworkGuard.isOnWifi()) return result
        val free = DownloadDiskInfo.volumeSpace()?.availableBytes
        if (free != null && free < SMART_DOWNLOAD_MIN_FREE_BYTES) return result
        val meta = MetaDetailsRepository.fetch(item.parentMetaType, item.parentMetaId) ?: return result
        val episodes = meta.videos.filter { it.season == season }.mapNotNull { it.episode }.distinct()
        var resolvedImdb: String? = null
        var imdbResolved = false
        val reference = pickReference(episode, ownMarks(item.parentMetaId, season), episodes) { e ->
            if (!imdbResolved) {
                imdbResolved = true
                resolvedImdb = resolveSkipImdbId(item.parentMetaId, null, "series")
            }
            onlineTimes(SkipIntroRepository.getSkipIntervals(resolvedImdb, season, e, requireSkipIntroEnabled = false), e)
        } ?: return result
        val video = meta.videos.firstOrNull { it.season == season && it.episode == reference.episode } ?: return result
        val (url, headers) = streamFor(meta, video, item) ?: return result
        val step = result.fingerprintStepMs.toLong().coerceAtLeast(1L)

        var updated = result
        if (result.introStartMs == null) {
            clipMatch(url, headers, decodeFingerprint(result.headFingerprint), 0L, step, reference.intro)?.let { (start, end) ->
                updated = updated.copy(introStartMs = start, introEndMs = end, foundUsing = updated.foundUsing + ("intro" to reference.episode))
            }
        }
        val credits = reference.credits
        if (credits != null && result.creditsStartMs == null) {
            clipMatch(url, headers, decodeFingerprint(result.tailFingerprint), result.tailStartMs, step, credits)?.let { (start, end) ->
                updated = updated.copy(creditsStartMs = start, creditsEndMs = end, foundUsing = updated.foundUsing + ("outro" to reference.episode))
            }
        }
        InAppLogger.info(
            "Analysis",
            "sibling clip e${reference.episode} for ${item.id}: intro=${updated.introStartMs}-${updated.introEndMs} " +
                "credits=${updated.creditsStartMs}-${updated.creditsEndMs}",
        )
        return updated
    }

    private suspend fun clipMatch(
        url: String,
        headers: Map<String, String>,
        frames: IntArray,
        framesOffsetMs: Long,
        step: Long,
        segment: Pair<Long, Long>,
    ): Pair<Long, Long>? {
        if (frames.isEmpty()) return null
        val text = MediaAnalyzerPlatform.fingerprintClip(url, headers, (segment.first - PAD_MS).coerceAtLeast(0L), segment.second + PAD_MS)
        val clip = text?.let { runCatching { json.decodeFromString(RawClipFingerprint.serializer(), it) }.getOrNull() } ?: return null
        if (clip.stepMs.toLong() != step) return null
        return locate(frames, framesOffsetMs, decodeFingerprint(clip.fingerprint), clip.startMs, step, segment)
    }

    /** Your marks on other episodes of the season (also those never downloaded or analysed). */
    private fun ownMarks(parentMetaId: String, season: Int): List<SiblingTimes> =
        MediaAnalysisStore.seasonResults(parentMetaId, season).mapNotNull { stored ->
            val episode = Regex("e(\\d+)$").find(stored.key)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val intro = stored.userSegments["intro"]?.takeIf { it.endMs > it.startMs && it.canTeach } ?: return@mapNotNull null
            val outro = stored.userSegments["outro"]?.takeIf { it.endMs > it.startMs && it.canTeach }
            SiblingTimes(episode, intro.startMs to intro.endMs, outro?.let { it.startMs to it.endMs })
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
     * Where [segment] (times in the sibling) falls in this episode: the clip, fingerprinted from
     * [clipStartMs], is matched against [frames] (starting at [framesOffsetMs]) and the segment
     * shifted by the offset between the two. Needs at least 8 s or half the segment to agree.
     */
    fun locate(
        frames: IntArray,
        framesOffsetMs: Long,
        clip: IntArray,
        clipStartMs: Long,
        step: Long,
        segment: Pair<Long, Long>,
    ): Pair<Long, Long>? {
        val minMs = maxOf(MIN_MATCH_MS, (segment.second - segment.first) / 2)
        val match = FingerprintMatcher.longestSharedSegment(frames, clip, minFrames = (minMs / step).toInt().coerceAtLeast(1))
            ?: return null
        val delta = (framesOffsetMs + match.startA * step) - (clipStartMs + match.startB * step)
        val start = (segment.first + delta).coerceAtLeast(0L)
        val end = segment.second + delta
        return if (end > start) start to end else null
    }
}
