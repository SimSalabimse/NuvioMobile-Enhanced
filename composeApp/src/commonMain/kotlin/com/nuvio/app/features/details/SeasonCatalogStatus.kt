package com.nuvio.app.features.details

import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.skip.FlagSubmissionRecord
import com.nuvio.app.features.player.skip.IntroSubmitService
import com.nuvio.app.features.player.skip.OwnAccountMarks
import com.nuvio.app.features.player.skip.PublicMediaStatus
import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.TheIntroDbMediaResponse
import com.nuvio.app.features.player.skip.applyAccountListing
import com.nuvio.app.features.player.skip.ownListBucketsForTitle
import com.nuvio.app.features.player.skip.publicSegmentFact
import com.nuvio.app.features.player.skip.savedKeyForService
import com.nuvio.app.features.player.skip.toFlagRecord
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Catalogue runtime is minutes. The media query wants milliseconds, and only when it is known. */
internal fun catalogueDurationMs(runtimeMinutes: Int?): Long? =
    runtimeMinutes?.takeIf { it > 0 }?.times(60_000L)

internal enum class CatalogRead {
    NotChecked,
    None,
    Unavailable,
    Listed,
}

/**
 * Public availability and this account's own submission, kept apart.
 * [ownConfirmed] is only this account. A public type never sets it.
 */
internal data class EpisodeCatalogFacts(
    val contentKey: String,
    val publicRead: CatalogRead,
    val publicTypes: Set<String>,
    val ownRead: CatalogRead,
    val ownConfirmed: Set<String>,
    val ownPending: Set<String>,
) {
    val ownNotSubmitted: Boolean
        get() = ownRead == CatalogRead.None && ownConfirmed.isEmpty() && ownPending.isEmpty()
}

internal data class SeasonStatusRequest(
    val imdbId: String,
    val season: Int,
    val episode: Int,
    val durationMs: Long?,
    val contentKey: String,
)

internal data class OwnListing(
    val failed: Boolean,
    val tmdbId: Int = 0,
    val records: List<FlagSubmissionRecord> = emptyList(),
)

internal data class PublicCacheEntry(
    val status: PublicMediaStatus,
    val media: TheIntroDbMediaResponse?,
    /** A failed read may be requested again after this time. A successful read has none. */
    val retryAfterMs: Long? = null,
)

/** One public GET. [retryUntilMs] is set only for HTTP 429, from the response deadline. */
internal data class PublicMediaFetch(
    val status: PublicMediaStatus,
    val media: TheIntroDbMediaResponse? = null,
    val retryUntilMs: Long? = null,
)

internal class SeasonPublicCache {
    val values = mutableMapOf<String, PublicCacheEntry>()
    var blockedUntilMs: Long = 0L

    fun key(request: SeasonStatusRequest): String =
        "${request.imdbId}:${request.season}:${request.episode}:${request.durationMs ?: 0L}"
}

/** One list per account scope. A failed list is not cached. */
internal object SeasonOwnCache {
    private val byScope = mutableMapOf<String, OwnListing>()

    fun get(scope: String): OwnListing? = byScope[scope]

    fun put(scope: String, listing: OwnListing) {
        if (scope.isBlank() || listing.failed) return
        byScope[scope] = listing
    }

    fun clearForTest() {
        byScope.clear()
    }
}

internal fun publicTypesOf(media: TheIntroDbMediaResponse?): Set<String> {
    if (media == null) return emptySet()
    return listOfNotNull(
        "intro".takeIf { publicSegmentFact(media.intro).available },
        "recap".takeIf { publicSegmentFact(media.recap).available },
        "outro".takeIf { publicSegmentFact(media.credits).available },
        "preview".takeIf { publicSegmentFact(media.preview).available },
    ).toSet()
}

internal fun factsForEpisode(
    contentKey: String,
    publicStatus: PublicMediaStatus?,
    media: TheIntroDbMediaResponse?,
    ownListed: Boolean,
    ownFailed: Boolean,
    merged: OwnAccountMarks,
): EpisodeCatalogFacts {
    val publicRead = when (publicStatus) {
        null -> CatalogRead.NotChecked
        PublicMediaStatus.Unavailable -> CatalogRead.Unavailable
        PublicMediaStatus.NoSubmissions -> CatalogRead.None
        PublicMediaStatus.Found -> if (publicTypesOf(media).isEmpty()) CatalogRead.None else CatalogRead.Listed
    }
    val publicTypes = if (publicRead == CatalogRead.Listed) publicTypesOf(media) else emptySet()
    val confirmed = merged.confirmed[contentKey].orEmpty()
    val pending = merged.pending[contentKey].orEmpty() - confirmed
    val ownRead = when {
        ownFailed -> CatalogRead.Unavailable
        confirmed.isNotEmpty() || pending.isNotEmpty() -> CatalogRead.Listed
        !ownListed -> CatalogRead.NotChecked
        else -> CatalogRead.None
    }
    return EpisodeCatalogFacts(
        contentKey = contentKey,
        publicRead = publicRead,
        publicTypes = publicTypes,
        ownRead = ownRead,
        ownConfirmed = confirmed,
        ownPending = pending,
    )
}

/**
 * Applies this account's listing onto [marks]. [other] is another account and is returned unchanged.
 * Public types are not an input.
 */
internal fun mergeOwnListing(
    marks: OwnAccountMarks,
    other: OwnAccountMarks,
    requests: List<SeasonStatusRequest>,
    listing: OwnListing,
): Pair<OwnAccountMarks, OwnAccountMarks> {
    if (listing.failed || listing.tmdbId <= 0) return marks to other
    var next = marks
    requests.forEach { request ->
        val buckets = ownListBucketsForTitle(
            listing.records,
            listing.tmdbId,
            isMovie = false,
            season = request.season,
            episode = request.episode,
        )
        next = applyAccountListing(
            marks = next,
            contentKey = request.contentKey,
            confirmed = buckets.confirmed,
            pending = buckets.pending,
            rejected = buckets.rejected,
        )
    }
    return next to other
}

internal const val SEASON_PUBLIC_BACKOFF_MS = 60_000L

/**
 * Fields that restart the season catalogue read.
 * [accountScope] is the hashed account, so two accounts do not reuse one load.
 */
internal fun seasonCatalogReloadKey(
    metaId: String,
    imdbId: String?,
    offline: Boolean,
    accountScope: String,
    contentKeys: List<String>,
): String = listOf(metaId, imdbId.orEmpty(), offline.toString(), accountScope, contentKeys.joinToString(","))
    .joinToString("|")

/**
 * Public reads go through [publicFetch], one episode at a time.
 * The first unavailable response backs off and does not call the rest.
 * An episode that was not requested stays not checked. A 429 keeps the server deadline.
 * A cached successful row is not fetched again. [ownListing] is one list for the title, not one list per episode.
 */
internal suspend fun loadSeasonCatalog(
    requests: List<SeasonStatusRequest>,
    ownListing: OwnListing?,
    merged: OwnAccountMarks,
    publicFetch: suspend (SeasonStatusRequest) -> PublicMediaFetch,
    cache: SeasonPublicCache,
    nowMs: Long,
): List<EpisodeCatalogFacts> {
    var blocked = cache.blockedUntilMs > nowMs
    val facts = mutableListOf<EpisodeCatalogFacts>()
    for (request in requests) {
        if (!currentCoroutineContext().isActive) throw CancellationException()
        val key = cache.key(request)
        val cached = cache.values[key]?.takeIf { entry ->
            entry.retryAfterMs == null || entry.retryAfterMs > nowMs
        }
        val fetched = when {
            request.imdbId.isBlank() -> null
            cached != null -> PublicMediaFetch(cached.status, cached.media)
            blocked -> null
            else -> {
                val read = publicFetch(request)
                if (read.status == PublicMediaStatus.Unavailable) {
                    val until = read.retryUntilMs ?: (nowMs + SEASON_PUBLIC_BACKOFF_MS)
                    if (until > cache.blockedUntilMs) cache.blockedUntilMs = until
                    cache.values[key] = PublicCacheEntry(read.status, null, retryAfterMs = until)
                    blocked = until > nowMs
                } else {
                    cache.values[key] = PublicCacheEntry(read.status, read.media)
                }
                read
            }
        }
        facts += factsForEpisode(
            contentKey = request.contentKey,
            publicStatus = fetched?.status,
            media = fetched?.media,
            ownListed = ownListing != null && !ownListing.failed && ownListing.tmdbId > 0,
            ownFailed = ownListing != null && (ownListing.failed || ownListing.tmdbId <= 0),
            merged = merged,
        )
    }
    return facts
}

internal val seasonPublicCache = SeasonPublicCache()

/**
 * This account's list for one show. Null when there is no key: that is "not checked", not "none".
 * A transport or review-list failure is [OwnListing.failed] and is not stored as "not submitted".
 */
internal suspend fun currentOwnListing(imdbId: String): OwnListing? {
    val settings = PlayerSettingsRepository.uiState.value
    val apiKey = savedKeyForService(
        IntroSubmitService.THE_INTRODB,
        settings.introDbApiKey,
        settings.theIntroDbApiKey,
    )
    if (apiKey.isBlank() || !imdbId.startsWith("tt")) return null
    val tmdbId = try {
        TmdbService.ensureTmdbId(videoId = imdbId, mediaType = "tv", fallbackImdbId = imdbId)?.toIntOrNull()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: return OwnListing(failed = true)
    if (tmdbId <= 0) return OwnListing(failed = true)
    return try {
        val records = TheIntroDb.listMySubmissions(apiKey, tmdbId, "tv").mapNotNull { it.toFlagRecord(tmdbId) }
        if (TheIntroDb.lastListError != null) OwnListing(failed = true) else OwnListing(failed = false, tmdbId = tmdbId, records = records)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OwnListing(failed = true)
    }
}

/** The last [TheIntroDb.getMedia] result, including a 429 deadline and excluding the header text. */
internal fun publicFetchFromLastGet(media: TheIntroDbMediaResponse?): PublicMediaFetch =
    PublicMediaFetch(
        status = TheIntroDb.lastPublicStatus,
        media = media,
        retryUntilMs = TheIntroDb.lastPublicRetryUntilMs,
    )

internal suspend fun productionPublicEpisode(
    request: SeasonStatusRequest,
): PublicMediaFetch {
    if (!request.imdbId.startsWith("tt")) return PublicMediaFetch(PublicMediaStatus.Unavailable)
    val media = TheIntroDb.getMedia(
        imdbId = request.imdbId,
        season = request.season,
        episode = request.episode,
        durationMs = request.durationMs,
    )
    return publicFetchFromLastGet(media)
}
