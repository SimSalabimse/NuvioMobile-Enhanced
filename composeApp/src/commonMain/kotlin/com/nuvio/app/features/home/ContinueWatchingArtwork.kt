package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/** How long a cached empty or failed artwork answer may suppress another fetch. */
internal const val MISSING_ARTWORK_RETRY_MS = 10L * 60L * 1000L

/**
 * Episode still, then the show backdrop, then the poster.
 * A blank stored field is a miss: the cached show value is used, and a blank cached value is not.
 * [imageUrl] is only a last resort when it is a different address.
 */
internal fun continueWatchingArtworkChain(
    episodeStill: String?,
    backdrop: String?,
    poster: String?,
    imageUrl: String? = null,
    cachedStill: String? = null,
    cachedBackdrop: String? = null,
    cachedPoster: String? = null,
): List<String> {
    fun pick(primary: String?, cached: String?): String? =
        primary?.trim()?.takeIf { it.isNotEmpty() }
            ?: cached?.trim()?.takeIf { it.isNotEmpty() }
    return listOfNotNull(
        pick(episodeStill, cachedStill),
        pick(backdrop, cachedBackdrop),
        pick(poster, cachedPoster),
        imageUrl?.trim()?.takeIf { it.isNotEmpty() },
    ).distinct()
}

/**
 * Fills blank artwork from the show already in the meta cache.
 * Does not write the Continue Watching progress entry.
 */
internal fun ContinueWatchingItem.fillingBlankArtworkFrom(meta: MetaDetails?): ContinueWatchingItem {
    if (meta == null) return this
    val video = if (seasonNumber != null && episodeNumber != null) {
        meta.videos.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber }
    } else {
        null
    }
    val still = video?.thumbnail?.takeIf { it.isNotBlank() }
    val backdrop = meta.background?.takeIf { it.isNotBlank() }
    val showPoster = meta.poster?.takeIf { it.isNotBlank() }
    val nextStill = episodeThumbnail.blankToNull() ?: still
    val nextBackdrop = background.blankToNull() ?: backdrop
    val nextPoster = poster.blankToNull() ?: showPoster
    val nextImage = imageUrl.blankToNull() ?: nextStill ?: nextBackdrop ?: nextPoster
    if (
        nextStill == episodeThumbnail &&
        nextBackdrop == background &&
        nextPoster == poster &&
        nextImage == imageUrl
    ) {
        return this
    }
    return copy(
        episodeThumbnail = nextStill,
        background = nextBackdrop,
        poster = nextPoster,
        imageUrl = nextImage,
    )
}

/** An episode that has aired and still has no still. A future episode is not a miss. */
internal fun metaHasAiredEpisodeWithoutStill(meta: MetaDetails?, todayIso: String): Boolean {
    if (meta == null || todayIso.isBlank()) return false
    return meta.videos.any { video ->
        video.episode != null &&
            video.thumbnail.isNullOrBlank() &&
            !video.released.isNullOrBlank() &&
            video.released <= todayIso
    }
}

/** A blank field, or a miss older than [MISSING_ARTWORK_RETRY_MS], is fetched again. */
internal fun missingArtworkRetryDue(
    lastAttemptMs: Long,
    nowMs: Long,
    ttlMs: Long = MISSING_ARTWORK_RETRY_MS,
): Boolean = lastAttemptMs <= 0L || nowMs - lastAttemptMs >= ttlMs

/**
 * A cached show whose episode has already aired and still has no still.
 * The poster can be present; that is not the episode picture.
 */
internal fun shouldBypassCachedMetaForMissingArtwork(
    meta: MetaDetails?,
    todayIso: String,
    lastAttemptMs: Long,
    nowMs: Long,
    offline: Boolean,
): Boolean {
    if (offline || meta == null) return false
    if (!metaHasAiredEpisodeWithoutStill(meta, todayIso)) return false
    return missingArtworkRetryDue(lastAttemptMs, nowMs)
}

/**
 * A Continue Watching row for an episode that has aired with no still stored.
 * A future episode is left alone. An empty release date is not treated as aired.
 */
internal fun cachedNextUpStillNeedsArtwork(
    released: String?,
    episodeThumbnail: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    todayIso: String,
): Boolean {
    if (seasonNumber == null || episodeNumber == null) return false
    if (!episodeThumbnail.isNullOrBlank()) return false
    val airDate = released?.trim().orEmpty()
    if (airDate.isEmpty() || todayIso.isBlank()) return false
    return airDate <= todayIso
}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
