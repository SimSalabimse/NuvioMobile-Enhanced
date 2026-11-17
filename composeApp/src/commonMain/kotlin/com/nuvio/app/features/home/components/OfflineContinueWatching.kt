package com.nuvio.app.features.home.components

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.downloadLogicalContentKey
import com.nuvio.app.features.watchprogress.CachedInProgressItem
import com.nuvio.app.features.watchprogress.CachedNextUpItem
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry

/**
 * Continue Watching saved for one profile, as the offline Home row should read it.
 * [Empty] and [Unreadable] both fall back to downloaded episodes that already have progress.
 */
internal sealed class OfflineContinueCache {
    data class Present(
        val nextUp: List<CachedNextUpItem>,
        val inProgress: List<CachedInProgressItem>,
    ) : OfflineContinueCache()

    data object Empty : OfflineContinueCache()

    data object Unreadable : OfflineContinueCache()
}

internal data class OfflineContinueCard(
    val key: String,
    val title: String,
    val imageUrl: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val fraction: Float,
    /** Dimmed cards are not downloaded (or not playable) and do not open. */
    val dimmed: Boolean,
    val tappable: Boolean,
    val download: DownloadItem?,
    val contentId: String = "",
    val contentType: String = "",
    val videoId: String = "",
)

internal fun OfflineContinueCard.toContinueWatchingItem(): ContinueWatchingItem? {
    val id = contentId.ifBlank { download?.parentMetaId }.orEmpty()
    if (id.isBlank()) return null
    val type = contentType.ifBlank { download?.parentMetaType }.orEmpty().ifBlank {
        if (seasonNumber != null) "series" else "movie"
    }
    return ContinueWatchingItem(
        parentMetaId = id,
        parentMetaType = type,
        videoId = videoId.ifBlank { download?.videoId ?: id },
        title = title,
        subtitle = episodeTitle.orEmpty(),
        imageUrl = imageUrl,
        poster = download?.poster ?: imageUrl,
        background = download?.background,
        logo = download?.logo,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        episodeThumbnail = download?.episodeThumbnail,
        resumePositionMs = 0L,
        durationMs = 0L,
        progressFraction = fraction,
    )
}

internal fun offlineContinueCacheFromSnapshots(
    nextUp: List<CachedNextUpItem>?,
    inProgress: List<CachedInProgressItem>?,
): OfflineContinueCache {
    if (nextUp == null || inProgress == null) return OfflineContinueCache.Unreadable
    if (nextUp.isEmpty() && inProgress.isEmpty()) return OfflineContinueCache.Empty
    return OfflineContinueCache.Present(nextUp = nextUp, inProgress = inProgress)
}

/**
 * Offline Continue Watching for the active profile.
 *
 * A readable cache is shown in the same order as the online row: in-progress and next-up merged by
 * the saved timestamps, one card per content id, season, and episode. A next-up card is left out
 * when that show already has an in-progress card. A playable download for that episode is tappable.
 * Anything else from the cache is dimmed. Downloaded episodes that are not in the cache but already
 * have watch progress are appended. An empty or unreadable cache keeps the downloaded-only row.
 */
internal fun offlineContinueWatchingCards(
    cache: OfflineContinueCache,
    downloads: List<DownloadItem>,
    progressByVideoId: Map<String, WatchProgressEntry>,
): List<OfflineContinueCard> {
    val present = cache as? OfflineContinueCache.Present
    val nextUp = present?.nextUp.orEmpty()
    val inProgress = present?.inProgress.orEmpty()
    if (present == null || (nextUp.isEmpty() && inProgress.isEmpty())) {
        return downloadedResumeCards(downloads, progressByVideoId)
    }

    val inProgressIds = inProgress.map { item -> item.contentId.trim() }.filter { it.isNotEmpty() }.toSet()
    val merged = buildList {
        inProgress.forEach { item ->
            if (item.contentId.isBlank()) return@forEach
            add(
                CacheRow(
                    contentId = item.contentId,
                    contentType = item.contentType,
                    videoId = item.videoId,
                    title = item.name,
                    imageUrl = item.episodeThumbnail ?: item.backdrop ?: item.poster,
                    season = item.season,
                    episode = item.episode,
                    episodeTitle = item.episodeTitle,
                    fraction = item.cachedFraction(),
                    timestamp = item.lastWatched,
                    fromProgress = true,
                ),
            )
        }
        nextUp.forEach { item ->
            if (item.contentId.isBlank()) return@forEach
            if (item.contentId.trim() in inProgressIds) return@forEach
            add(
                CacheRow(
                    contentId = item.contentId,
                    contentType = item.contentType,
                    videoId = item.videoId,
                    title = item.name,
                    imageUrl = item.episodeThumbnail ?: item.backdrop ?: item.poster,
                    season = item.season,
                    episode = item.episode,
                    episodeTitle = item.episodeTitle,
                    fraction = 0f,
                    timestamp = item.sortTimestamp,
                    fromProgress = false,
                ),
            )
        }
    }.sortedWith(
        compareByDescending<CacheRow> { row -> row.timestamp }
            .thenByDescending { row -> row.fromProgress },
    )

    val seen = mutableSetOf<String>()
    val cacheKeys = buildSet {
        inProgress.forEach { item -> add(episodeKey(item.contentId, item.season, item.episode)) }
        nextUp.forEach { item -> add(episodeKey(item.contentId, item.season, item.episode)) }
    }
    val playableByKey = downloads
        .filter { item -> item.isPlayable }
        .groupBy { item -> episodeKey(item.parentMetaId, item.seasonNumber, item.episodeNumber) }
        .mapValues { (_, group) -> group.maxBy { item -> item.updatedAtEpochMs } }

    val fromCache = merged.mapNotNull { row ->
        val key = episodeKey(row.contentId, row.season, row.episode)
        if (!seen.add(key)) return@mapNotNull null
        val download = playableByKey[key]
        OfflineContinueCard(
            key = "cache:$key",
            title = row.title,
            imageUrl = row.imageUrl,
            seasonNumber = row.season,
            episodeNumber = row.episode,
            episodeTitle = row.episodeTitle,
            fraction = row.fraction,
            dimmed = download == null,
            tappable = download != null,
            download = download,
            contentId = row.contentId,
            contentType = row.contentType,
            videoId = row.videoId,
        )
    }

    val extras = downloadedResumeCards(downloads, progressByVideoId)
        .filter { card ->
            val item = card.download ?: return@filter false
            episodeKey(item.parentMetaId, item.seasonNumber, item.episodeNumber) !in cacheKeys
        }
        .map { card -> card.copy(key = "extra:${card.download?.id ?: card.key}") }

    return fromCache + extras
}

private data class CacheRow(
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val title: String,
    val imageUrl: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val fraction: Float,
    val timestamp: Long,
    val fromProgress: Boolean,
)

private fun downloadedResumeCards(
    downloads: List<DownloadItem>,
    progressByVideoId: Map<String, WatchProgressEntry>,
): List<OfflineContinueCard> =
    downloads.mapNotNull { item ->
        val entry = progressByVideoId[item.videoId] ?: return@mapNotNull null
        if (!entry.countsForOfflineResume()) return@mapNotNull null
        val fraction = entry.resumeFraction()
        Triple(item, fraction, entry.lastUpdatedEpochMs)
    }.sortedByDescending { (_, _, updated) -> updated }
        .take(OfflineDownloadedResumeLimit)
        .map { (item, fraction, _) ->
            OfflineContinueCard(
                key = item.id,
                title = item.title,
                imageUrl = item.episodeThumbnail ?: item.background ?: item.poster,
                seasonNumber = item.seasonNumber,
                episodeNumber = item.episodeNumber,
                episodeTitle = item.episodeTitle,
                fraction = fraction,
                dimmed = false,
                tappable = true,
                download = item,
                contentId = item.parentMetaId,
                contentType = item.parentMetaType,
                videoId = item.videoId,
            )
        }

private fun CachedInProgressItem.cachedFraction(): Float {
    progressPercent?.let { percent ->
        return (percent / 100f).coerceIn(0f, 1f)
    }
    if (duration > 0L) return (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    return 0f
}

/** Same window the downloaded-only row used: started, and not essentially finished. */
private fun WatchProgressEntry.countsForOfflineResume(): Boolean {
    if (isCompleted) return false
    val fraction = resumeFraction()
    return fraction >= 0.02f && fraction <= 0.95f
}

private fun WatchProgressEntry.resumeFraction(): Float =
    if (durationMs > 0L) (lastPositionMs.toFloat() / durationMs.toFloat()) else 0f

private fun episodeKey(contentId: String, season: Int?, episode: Int?): String =
    downloadLogicalContentKey(contentId, season, episode)

private const val OfflineDownloadedResumeLimit = 10
