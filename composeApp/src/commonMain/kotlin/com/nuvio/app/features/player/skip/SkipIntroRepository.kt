package com.nuvio.app.features.player.skip

import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.PlayerSettingsRepository
import kotlinx.coroutines.CancellationException
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

object SkipIntroRepository {

    private val cache = HashMap<String, List<SkipInterval>>()
    private val imdbEntriesCache = HashMap<String, List<ArmEntry>>()
    private val animeSkipShowIdCache = HashMap<String, String>()
    private const val NO_ID = "__none__"
    private const val INTRO_DB_TIMEOUT_MS = 5_000L
    private const val THE_INTRO_DB_TIMEOUT_MS = 5_000L
    private const val ARM_LOOKUP_TIMEOUT_MS = 3_000L
    private const val ANISKIP_TIMEOUT_MS = 3_000L
    private const val ANIME_SKIP_TIMEOUT_MS = 3_000L
    private const val ANIME_SKIP_SHOW_LOOKUP_TIMEOUT_MS = 3_000L

    private val introDbConfigured: Boolean
        get() = IntroDbConfig.URL.isNotBlank()

    suspend fun getMovieSkipIntervals(
        contentId: String?,
        videoId: String?,
        requireSkipIntroEnabled: Boolean = true,
    ): List<SkipInterval> = coroutineScope {
        if (requireSkipIntroEnabled && !PlayerSettingsRepository.uiState.value.skipIntroEnabled) {
            return@coroutineScope emptyList()
        }
        val imdbId = resolveMovieSkipImdbId(
            contentId, videoId,
            resolveTmdb = { TmdbService.tmdbToImdb(it, "movie") },
            resolveAnime = { source, id -> SimklIdResolver.resolveIds(source, id)?.imdb },
        )
        if (imdbId == null) return@coroutineScope emptyList()
        val cacheKey = "movie:$imdbId"
        cache[cacheKey]?.let { return@coroutineScope it }

        val theIntroDbDeferred = async { fetchMovieFromTheIntroDb(imdbId) }
        val introDbDeferred = async {
            if (introDbConfigured) {
                SkipIntroApi.getIntroDbMovieSegments(imdbId)?.movieSkipIntervals().orEmpty()
            } else emptyList()
        }
        mergeByPriority(theIntroDbDeferred.await(), introDbDeferred.await()).also {
            cache[cacheKey] = it
        }
    }

    suspend fun getSkipIntervals(
        imdbId: String?,
        season: Int,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
    ): List<SkipInterval> = coroutineScope {
        if (imdbId == null) {
            InAppLogger.debug("Player/SkipIntro", "skip lookup ignored: imdbId missing s=$season e=$episode")
            return@coroutineScope emptyList()
        }
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) {
            InAppLogger.debug("Player/SkipIntro", "skip lookup disabled imdb=$imdbId s=$season e=$episode")
            return@coroutineScope emptyList()
        }

        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { cached ->
            InAppLogger.debug("Player/SkipIntro", "skip lookup cache hit imdb=$imdbId s=$season e=$episode count=${cached.size}")
            return@coroutineScope cached
        }
        InAppLogger.info(
            "Player/SkipIntro",
            "skip lookup imdb=$imdbId s=$season e=$episode introDb=$introDbConfigured " +
                "animeSkip=${settings.animeSkipEnabled}",
        )

        val theIntroDbDeferred = async { fetchFromTheIntroDb(imdbId, season, episode) }
        val introDbDeferred = async {
            if (introDbConfigured) fetchFromIntroDb(imdbId, season, episode) else emptyList()
        }
        val simklIdsDeferred = async { SimklIdResolver.resolveIdsForImdbEpisode(imdbId, season, episode) }

        val theIntroDb = theIntroDbDeferred.await()
        val introDb = introDbDeferred.await()
        val primary = mergeByPriority(theIntroDb, introDb)
        if (primary.hasOpeningSegment()) {
            simklIdsDeferred.cancel()
            InAppLogger.info(
                "Player/SkipIntro",
                "skip lookup fast result imdb=$imdbId s=$season e=$episode count=${primary.size} " +
                    "theintrodb=${theIntroDb.size} introdb=${introDb.size}",
            )
            cache[cacheKey] = primary
            return@coroutineScope primary
        }

        val simklIds = simklIdsDeferred.await()
        val malId = simklIds?.mal
        val anilistId = simklIds?.anilist

        val animeEpisode = if (simklIds != null) {
            val mapping = SimklIdResolver.getEpisodeMapping(simklIds.simklId, simklIds.type)
            mapping.firstOrNull { it.tvdbSeason == season && it.tvdbEpisode == episode }
                ?.animeEpisode
                ?: episode
        } else episode

        val aniSkipDeferred = async {
            if (malId != null) fetchFromAniSkip(malId, animeEpisode) else emptyList()
        }
        val animeSkipDeferred = async {
            if (anilistId != null) fetchFromAnimeSkip(anilistId, animeEpisode, season = null) else emptyList()
        }

        val animeSkip = animeSkipDeferred.await()
        val aniSkip = aniSkipDeferred.await()
        return@coroutineScope mergeByPriority(
            theIntroDb,
            introDb,
            animeSkip,
            aniSkip,
        ).also { merged ->
            InAppLogger.info(
                "Player/SkipIntro",
                "skip lookup result imdb=$imdbId s=$season e=$episode count=${merged.size} " +
                    "theintrodb=${theIntroDb.size} introdb=${introDb.size} " +
                    "animeskip=${animeSkip.size} aniskip=${aniSkip.size}",
            )
            cache[cacheKey] = merged
        }
    }

    suspend fun getSkipIntervalsForMal(
        malId: String,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
    ): List<SkipInterval> = coroutineScope {
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) {
            InAppLogger.debug("Player/SkipIntro", "skip lookup disabled mal=$malId e=$episode")
            return@coroutineScope emptyList()
        }

        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let { cached ->
            InAppLogger.debug("Player/SkipIntro", "skip lookup cache hit mal=$malId e=$episode count=${cached.size}")
            return@coroutineScope cached
        }
        InAppLogger.info("Player/SkipIntro", "skip lookup mal=$malId e=$episode")

        val aniSkipDeferred = async { fetchFromAniSkip(malId, episode) }

        val imdbIdDeferred = async {
            try {
                SkipIntroApi.resolveMalToImdb(malId)?.imdb
            } catch (_: Exception) { null }
        }

        var introDb = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        val resolvedImdb = imdbIdDeferred.await()
        if (resolvedImdb != null) {
            val entries = resolveImdbEntries(resolvedImdb)
            val season = entries.indexOfFirst { it.myanimelist == malId.toIntOrNull() } + 1
            val theIntroDbDeferred = async { fetchFromTheIntroDb(resolvedImdb, season, episode) }
            val introDbDeferred = async {
                if (introDbConfigured) fetchFromIntroDb(resolvedImdb, season, episode) else emptyList()
            }
            val animeSkipDeferred = async { fetchAnimeSkipForEntries(entries, season, episode) }
            introDb = mergeByPriority(theIntroDbDeferred.await(), introDbDeferred.await())
            animeSkip = animeSkipDeferred.await()
        } else {
            val anilistId = try {
                SkipIntroApi.resolveMalToAnilist(malId)?.anilist?.toString()
            } catch (_: Exception) { null }
            if (anilistId != null) animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }

        val aniSkip = aniSkipDeferred.await()
        return@coroutineScope mergeByPriority(introDb, animeSkip, aniSkip).also { merged ->
            InAppLogger.info(
                "Player/SkipIntro",
                "skip lookup result mal=$malId e=$episode count=${merged.size} " +
                    "introdb=${introDb.size} animeskip=${animeSkip.size} aniskip=${aniSkip.size}",
            )
            cache[cacheKey] = merged
        }
    }

    suspend fun getSkipIntervalsForKitsu(
        kitsuId: String,
        episode: Int,
        requireSkipIntroEnabled: Boolean = true,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null,
    ): List<SkipInterval> = coroutineScope {
        val settings = PlayerSettingsRepository.uiState.value
        if (requireSkipIntroEnabled && !settings.skipIntroEnabled) {
            InAppLogger.debug("Player/SkipIntro", "skip lookup disabled kitsu=$kitsuId e=$episode")
            return@coroutineScope emptyList()
        }

        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let { cached ->
            InAppLogger.debug("Player/SkipIntro", "skip lookup cache hit kitsu=$kitsuId e=$episode count=${cached.size}")
            return@coroutineScope cached
        }
        InAppLogger.info("Player/SkipIntro", "skip lookup kitsu=$kitsuId e=$episode")

        val malIdDeferred = async {
            try {
                SkipIntroApi.resolveKitsuToMal(kitsuId)?.myanimelist?.toString()
            } catch (_: Exception) { null }
        }
        val imdbIdDeferred = async {
            try {
                SkipIntroApi.resolveKitsuToImdb(kitsuId)?.imdb
            } catch (_: Exception) { null }
        }
        val aniSkipDeferred = async {
            malIdDeferred.await()?.let { fetchFromAniSkip(it, episode) } ?: emptyList()
        }

        var introDb = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        val resolvedImdb = imdbIdDeferred.await()
        if (resolvedImdb != null) {
            val entries = resolveImdbEntries(resolvedImdb)
            val season = entries.indexOfFirst { it.kitsu == kitsuId.toIntOrNull() } + 1
            val theIntroDbDeferred = async { fetchFromTheIntroDb(resolvedImdb, season, episode) }
            val introDbDeferred = async {
                if (introDbConfigured) fetchFromIntroDb(resolvedImdb, season, episode) else emptyList()
            }
            val animeSkipDeferred = async { fetchAnimeSkipForEntries(entries, season, episode) }
            introDb = mergeByPriority(theIntroDbDeferred.await(), introDbDeferred.await())
            animeSkip = animeSkipDeferred.await()
        } else {
            val anilistId = try {
                SkipIntroApi.resolveKitsuToAnilist(kitsuId)?.anilist?.toString()
            } catch (_: Exception) { null }
            if (anilistId != null) animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }

        val aniSkip = aniSkipDeferred.await()
        return@coroutineScope mergeByPriority(introDb, animeSkip, aniSkip).also { merged ->
            InAppLogger.info(
                "Player/SkipIntro",
                "skip lookup result kitsu=$kitsuId e=$episode count=${merged.size} " +
                    "introdb=${introDb.size} animeskip=${animeSkip.size} aniskip=${aniSkip.size}",
            )
            cache[cacheKey] = merged
        }
    }

    private fun mergeByPriority(vararg providerResults: List<SkipInterval>): List<SkipInterval> {
        val chosen = LinkedHashMap<String, SkipInterval>()
        for (result in providerResults) {
            for (interval in result) {
                val category = segmentCategory(interval.type) ?: continue
                if (category !in chosen) chosen[category] = interval
            }
        }
        return chosen.values.toList()
    }

    private fun segmentCategory(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op" -> "opening"
        "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "ending"
        "post-credits" -> "post-credits"
        "preview" -> "preview"
        "recap" -> "recap"
        else -> null
    }

    private fun List<SkipInterval>.hasOpeningSegment(): Boolean = any { interval ->
        segmentCategory(interval.type) == "opening"
    }

    private suspend fun <T> withSkipProviderTimeout(
        provider: String,
        timeoutMs: Long,
        fallback: T,
        block: suspend () -> T,
    ): T {
        val result = withTimeoutOrNull(timeoutMs) { block() }
        if (result == null) {
            InAppLogger.warn("Player/SkipIntro", "$provider timed out after ${timeoutMs}ms")
            return fallback
        }
        return result
    }

    private suspend fun fetchAnimeSkipForEntries(
        entries: List<ArmEntry>,
        season: Int,
        episode: Int
    ): List<SkipInterval> {
        val seasonAnilistId = entries.getOrNull(season - 1)?.anilist?.toString()
        val fallbackAnilistId = entries.firstOrNull()?.anilist?.toString()
        for ((anilistId, seasonFilter) in listOfNotNull(
            seasonAnilistId?.let { it to null },
            if (fallbackAnilistId != null && fallbackAnilistId != seasonAnilistId) fallbackAnilistId to season else null
        )) {
            val result = fetchFromAnimeSkip(anilistId, episode, season = seasonFilter)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private suspend fun fetchFromTheIntroDb(
        imdbId: String,
        season: Int,
        episode: Int,
    ): List<SkipInterval> = withSkipProviderTimeout("TheIntroDB", THE_INTRO_DB_TIMEOUT_MS, emptyList()) {
        try {
            TheIntroDb.fetchTvIntervals(imdbId = imdbId, season = season, episode = episode)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchMovieFromTheIntroDb(imdbId: String): List<SkipInterval> =
        withSkipProviderTimeout("TheIntroDB", THE_INTRO_DB_TIMEOUT_MS, emptyList()) {
            try {
                TheIntroDb.fetchMovieIntervals(imdbId = imdbId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return withSkipProviderTimeout("IntroDB", INTRO_DB_TIMEOUT_MS, emptyList()) {
            try {
                val data = SkipIntroApi.getIntroDbSegments(imdbId, season, episode)
                if (data == null) emptyList() else listOfNotNull(
                    data.intro.toSkipIntervalOrNull("intro"),
                    data.recap.toSkipIntervalOrNull("recap"),
                    data.outro.toSkipIntervalOrNull("outro"),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return withSkipProviderTimeout("AniSkip", ANISKIP_TIMEOUT_MS, emptyList()) {
            try {
                val response = SkipIntroApi.getAniSkipTimes(malId, episode)
                when {
                    response == null || !response.found -> emptyList()
                    else -> response.results?.map { result ->
                        SkipInterval(
                            startTime = result.interval.startTime,
                            endTime = result.interval.endTime,
                            type = result.skipType,
                            provider = "aniskip",
                        )
                    } ?: emptyList()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        val clientId = settings.animeSkipClientId.trim()
        if (clientId.isBlank() || !settings.animeSkipEnabled) return emptyList()

        return withSkipProviderTimeout("AnimeSkip", ANIME_SKIP_TIMEOUT_MS, emptyList()) {
            try {
                val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
                if (showIds.isEmpty()) return@withSkipProviderTimeout emptyList()
                for (showId in showIds) {
                    val query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                    val response = SkipIntroApi.queryAnimeSkip(clientId, query) ?: continue
                    val episodes = response.data?.findEpisodesByShowId ?: continue
                    val targetEpisode = episodes.firstOrNull { ep ->
                        ep.number?.toIntOrNull() == episode &&
                            (season == null || ep.season?.toIntOrNull() == season)
                    } ?: continue
                    val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                    val result = sorted.mapIndexedNotNull { i, ts ->
                        val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                        val type = when (ts.type.name.lowercase()) {
                            "intro", "new intro" -> "op"
                            "credits" -> "ed"
                            "recap" -> "recap"
                            else -> return@mapIndexedNotNull null
                        }
                        SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                    }
                    if (result.isNotEmpty()) return@withSkipProviderTimeout result
                }
                emptyList()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
        val showIds = withSkipProviderTimeout(
            provider = "AnimeSkip show resolve",
            timeoutMs = ANIME_SKIP_SHOW_LOOKUP_TIMEOUT_MS,
            fallback = emptyList(),
        ) {
            try {
                SkipIntroApi.queryAnimeSkip(clientId, query)
                    ?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    private suspend fun resolveImdbEntries(imdbId: String): List<ArmEntry> {
        imdbEntriesCache[imdbId]?.let { return it }
        return withSkipProviderTimeout("ARM resolve", ARM_LOOKUP_TIMEOUT_MS, emptyList()) {
            try {
                SkipIntroApi.resolveImdbToAll(imdbId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }.also { imdbEntriesCache[imdbId] = it }
    }

    suspend fun submitIntro(
        imdbId: String,
        season: Int,
        episode: Int,
        startSec: Double,
        endSec: Double,
        segmentType: String,
    ): Boolean {
        val settings = PlayerSettingsRepository.uiState.value
        val apiKey = settings.introDbApiKey.trim()
        if (!settings.introSubmitEnabled || apiKey.isBlank()) return false
        val request = SubmitIntroRequest(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            startMs = (startSec * 1000).toLong(),
            endMs = (endSec * 1000).toLong(),
            segmentType = segmentType,
        )
        return try {
            SkipIntroApi.submitIntro(apiKey, request)
        } catch (error: Exception) {
            InAppLogger.warn(
                "Player/SkipIntro",
                "submit failed imdb=$imdbId s=$season e=$episode type=$segmentType " +
                    "error=${InAppLogger.throwableSummary(error)}",
            )
            throw error
        }
    }

    suspend fun verifyIntroDbApiKey(apiKey: String): Boolean {
        return try {
            SkipIntroApi.verifyIntroDbApiKey(apiKey)
        } catch (error: Exception) {
            InAppLogger.warn(
                "Player/SkipIntro",
                "verify IntroDB api key failed error=${InAppLogger.throwableSummary(error)}",
            )
            throw error
        }
    }

    fun clearCache() {
        cache.clear()
        imdbEntriesCache.clear()
        animeSkipShowIdCache.clear()
    }
}

internal suspend fun resolveMovieSkipImdbId(
    contentId: String?,
    videoId: String?,
    resolveTmdb: suspend (Int) -> String?,
    resolveAnime: suspend (String, String) -> String?,
): String? {
    val ids = listOfNotNull(contentId, videoId).map(String::trim).distinct()
    val imdbPattern = Regex("tt[0-9]+")
    ids.map { it.substringBefore(':') }.firstOrNull { imdbPattern.matches(it) }?.let { return it }
    return ids.firstNotNullOfOrNull { id ->
        val parts = id.split(':')
        val value = parts.getOrNull(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return@firstNotNullOfOrNull null
        when (parts.first().lowercase()) {
            "tmdb" -> value.toIntOrNull()?.takeIf { it > 0 }?.let { resolveTmdb(it) }
            "mal", "kitsu" -> resolveAnime(parts.first().lowercase(), value)
            else -> null
        }?.trim()?.takeIf { imdbPattern.matches(it) }
    }
}
