package com.nuvio.app.features.details

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class OpenedTitleHit(
    val meta: MetaDetails,
    val savedAtMs: Long,
    val fresh: Boolean,
)

@Serializable
internal data class StoredExternalRating(
    val source: String,
    val value: Double,
    val isCertified: Boolean = false,
)

@Serializable
internal data class StoredMetaPerson(
    val name: String,
    val role: String? = null,
    val photo: String? = null,
    val tmdbId: Int? = null,
)

@Serializable
internal data class StoredMetaCompany(
    val name: String,
    val logo: String? = null,
)

@Serializable
internal data class StoredMetaTrailer(
    val id: String,
    val key: String,
    val name: String,
    val site: String,
    val size: Int? = null,
    val type: String = "Trailer",
    val official: Boolean = false,
    val publishedAt: String? = null,
    val seasonNumber: Int? = null,
    val displayName: String? = null,
    val iso6391: String? = null,
)

@Serializable
internal data class StoredMetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val posterShape: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
internal data class StoredMetaVideo(
    val id: String,
    val title: String,
    val released: String? = null,
    val available: Boolean = true,
    val thumbnail: String? = null,
    val seasonPoster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val runtime: Int? = null,
    val tmdbRating: Double? = null,
    val imdbRating: Double? = null,
    val rating: Double? = null,
    val imdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
)

@Serializable
internal data class StoredMetaDetails(
    val id: String,
    val type: String,
    val name: String,
    val imdbId: String? = null,
    val tvdbId: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val lastAirDate: String? = null,
    val status: String? = null,
    val imdbRating: String? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val externalRatings: List<StoredExternalRating> = emptyList(),
    val genres: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val writer: List<String> = emptyList(),
    val cast: List<StoredMetaPerson> = emptyList(),
    val productionCompanies: List<StoredMetaCompany> = emptyList(),
    val networks: List<StoredMetaCompany> = emptyList(),
    val country: String? = null,
    val awards: String? = null,
    val language: String? = null,
    val budget: Long? = null,
    val revenue: Long? = null,
    val hasScheduledVideos: Boolean = false,
    val defaultVideoId: String? = null,
    val moreLikeThis: List<StoredMetaPreview> = emptyList(),
    val moreLikeThisSource: String? = null,
    val moreLikeThisHasMore: Boolean = false,
    val collectionName: String? = null,
    val collectionItems: List<StoredMetaPreview> = emptyList(),
    val trailers: List<StoredMetaTrailer> = emptyList(),
    val seasonPosters: Map<String, String> = emptyMap(),
    val videos: List<StoredMetaVideo> = emptyList(),
)

internal fun renderUrl(url: String?): String? {
    val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val schemeSplit = trimmed.substringAfter("://", missingDelimiterValue = "")
    if (schemeSplit.isNotEmpty()) {
        val authority = schemeSplit.substringBefore('/')
        if ('@' in authority) return null
    }
    val query = trimmed.substringAfter('?', missingDelimiterValue = "").lowercase()
    if (
        query.contains("api_key=") ||
        query.contains("apikey=") ||
        query.contains("token=") ||
        query.contains("access_token=") ||
        query.contains("key=")
    ) {
        return null
    }
    return trimmed
}

internal fun MetaDetails.toStoredRenderMeta(): StoredMetaDetails = StoredMetaDetails(
    id = id,
    type = type,
    name = name,
    imdbId = imdbId,
    tvdbId = tvdbId,
    poster = renderUrl(poster),
    background = renderUrl(background),
    logo = renderUrl(logo),
    description = description,
    releaseInfo = releaseInfo,
    lastAirDate = lastAirDate,
    status = status,
    imdbRating = imdbRating,
    ageRating = ageRating,
    runtime = runtime,
    externalRatings = externalRatings.map { rating ->
        StoredExternalRating(
            source = rating.source,
            value = rating.value,
            isCertified = rating.isCertified,
        )
    },
    genres = genres,
    director = director,
    writer = writer,
    cast = cast.map { person ->
        StoredMetaPerson(
            name = person.name,
            role = person.role,
            photo = renderUrl(person.photo),
            tmdbId = navigablePersonId(person.tmdbId),
        )
    },
    productionCompanies = productionCompanies.map { company ->
        StoredMetaCompany(name = company.name, logo = renderUrl(company.logo))
    },
    networks = networks.map { company ->
        StoredMetaCompany(name = company.name, logo = renderUrl(company.logo))
    },
    country = country,
    awards = awards,
    language = language,
    budget = budget,
    revenue = revenue,
    hasScheduledVideos = hasScheduledVideos,
    defaultVideoId = defaultVideoId,
    moreLikeThis = moreLikeThis.map { it.toStoredPreview() },
    moreLikeThisSource = moreLikeThisSource?.name,
    moreLikeThisHasMore = moreLikeThisHasMore,
    collectionName = collectionName,
    collectionItems = collectionItems.map { it.toStoredPreview() },
    trailers = trailers.map { trailer ->
        StoredMetaTrailer(
            id = trailer.id,
            key = trailer.key,
            name = trailer.name,
            site = trailer.site,
            size = trailer.size,
            type = trailer.type,
            official = trailer.official,
            publishedAt = trailer.publishedAt,
            seasonNumber = trailer.seasonNumber,
            displayName = trailer.displayName,
            iso6391 = trailer.iso6391,
        )
    },
    seasonPosters = seasonPosters.mapNotNull { (season, poster) ->
        renderUrl(poster)?.let { season.toString() to it }
    }.toMap(),
    videos = videos.map { video -> video.toStoredVideo() },
)

internal fun StoredMetaDetails.toMetaDetails(): MetaDetails = MetaDetails(
    id = id,
    type = type,
    name = name,
    imdbId = imdbId,
    tvdbId = tvdbId,
    poster = poster,
    background = background,
    logo = logo,
    description = description,
    releaseInfo = releaseInfo,
    lastAirDate = lastAirDate,
    status = status,
    imdbRating = imdbRating,
    ageRating = ageRating,
    runtime = runtime,
    externalRatings = externalRatings.map { rating ->
        MetaExternalRating(
            source = rating.source,
            value = rating.value,
            isCertified = rating.isCertified,
        )
    },
    genres = genres,
    director = director,
    writer = writer,
    cast = cast.map { person ->
        MetaPerson(
            name = person.name,
            role = person.role,
            photo = person.photo,
            tmdbId = navigablePersonId(person.tmdbId),
        )
    },
    productionCompanies = productionCompanies.map { company ->
        MetaCompany(name = company.name, logo = company.logo)
    },
    networks = networks.map { company ->
        MetaCompany(name = company.name, logo = company.logo)
    },
    country = country,
    awards = awards,
    language = language,
    budget = budget,
    revenue = revenue,
    hasScheduledVideos = hasScheduledVideos,
    defaultVideoId = defaultVideoId,
    moreLikeThis = moreLikeThis.map { it.toMetaPreview() },
    moreLikeThisSource = moreLikeThisSource?.let { name ->
        MoreLikeThisSource.entries.firstOrNull { it.name == name }
    },
    moreLikeThisHasMore = moreLikeThisHasMore,
    collectionName = collectionName,
    collectionItems = collectionItems.map { it.toMetaPreview() },
    trailers = trailers.map { trailer ->
        MetaTrailer(
            id = trailer.id,
            key = trailer.key,
            name = trailer.name,
            site = trailer.site,
            size = trailer.size,
            type = trailer.type,
            official = trailer.official,
            publishedAt = trailer.publishedAt,
            seasonNumber = trailer.seasonNumber,
            displayName = trailer.displayName,
            iso6391 = trailer.iso6391,
        )
    },
    seasonPosters = seasonPosters.mapNotNull { (season, poster) ->
        season.toIntOrNull()?.let { it to poster }
    }.toMap(),
    videos = videos.map { video -> video.toMetaVideo() },
)

private fun MetaPreview.toStoredPreview(): StoredMetaPreview = StoredMetaPreview(
    id = id,
    type = type,
    name = name,
    poster = renderUrl(poster),
    banner = renderUrl(banner),
    logo = renderUrl(logo),
    posterShape = posterShape.name,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
)

private fun StoredMetaPreview.toMetaPreview(): MetaPreview = MetaPreview(
    id = id,
    type = type,
    name = name,
    poster = poster,
    banner = banner,
    logo = logo,
    posterShape = PosterShape.entries.firstOrNull { it.name == posterShape } ?: PosterShape.Poster,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
)

private fun MetaVideo.toStoredVideo(): StoredMetaVideo = StoredMetaVideo(
    id = id,
    title = title,
    released = released,
    available = available,
    thumbnail = renderUrl(thumbnail),
    seasonPoster = renderUrl(seasonPoster),
    season = season,
    episode = episode,
    overview = overview,
    runtime = runtime,
    tmdbRating = tmdbRating,
    imdbRating = imdbRating,
    rating = rating,
    imdbId = imdbId,
    imdbSeason = imdbSeason,
    imdbEpisode = imdbEpisode,
)

private fun StoredMetaVideo.toMetaVideo(): MetaVideo = MetaVideo(
    id = id,
    title = title,
    released = released,
    available = available,
    thumbnail = thumbnail,
    seasonPoster = seasonPoster,
    season = season,
    episode = episode,
    overview = overview,
    runtime = runtime,
    tmdbRating = tmdbRating,
    imdbRating = imdbRating,
    rating = rating,
    imdbId = imdbId,
    imdbSeason = imdbSeason,
    imdbEpisode = imdbEpisode,
)

internal object OpenedTitleDisk {
    private val json = Json { ignoreUnknownKeys = true }
    private val titles = productionOfflineDiskCache(
        namespace = "meta-details",
        maxEntries = OfflineDetailsCacheLimits.MetaMaxEntries,
        maxBytes = OfflineDetailsCacheLimits.MetaMaxBytes,
        ttlMs = OfflineDetailsCacheLimits.MetaTtlMs,
    )

    suspend fun read(profileKey: String, requestKey: String): OpenedTitleHit? = withContext(Dispatchers.Default) {
        val hit = titles.read(profileKey, requestKey) ?: return@withContext null
        val stored = runCatching {
            json.decodeFromString(StoredMetaDetails.serializer(), hit.payload)
        }.getOrNull() ?: return@withContext null
        if (stored.id.isBlank()) return@withContext null
        val meta = stored.toMetaDetails().withSeasonEpisodes(profileKey, requestKey)
        OpenedTitleHit(
            meta = meta,
            savedAtMs = hit.savedAtMs,
            fresh = hit.fresh,
        )
    }

    fun save(profileKey: String, requestKey: String, meta: MetaDetails, savedAtMs: Long) {
        val stored = meta.toStoredRenderMeta()
        val payload = runCatching {
            json.encodeToString(StoredMetaDetails.serializer(), stored)
        }.getOrNull() ?: return
        titles.upsert(profileKey, requestKey, payload, savedAtMs)
        SeasonEpisodeDisk.save(profileKey, requestKey, stored.videos, savedAtMs)
    }

    private fun MetaDetails.withSeasonEpisodes(profileKey: String, requestKey: String): MetaDetails {
        if (videos.isNotEmpty()) return this
        val episodes = SeasonEpisodeDisk.read(profileKey, requestKey).orEmpty()
        if (episodes.isEmpty()) return this
        return copy(videos = episodes.map { video ->
            MetaVideo(
                id = video.id,
                title = video.title,
                released = video.released,
                available = video.available,
                thumbnail = video.thumbnail,
                seasonPoster = video.seasonPoster,
                season = video.season,
                episode = video.episode,
                overview = video.overview,
                runtime = video.runtime,
                tmdbRating = video.tmdbRating,
                imdbRating = video.imdbRating,
                rating = video.rating,
                imdbId = video.imdbId,
                imdbSeason = video.imdbSeason,
                imdbEpisode = video.imdbEpisode,
            )
        })
    }
}
