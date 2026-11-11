package com.nuvio.app.features.player.skip

import com.nuvio.app.core.automation.AutomationTestMode
import com.nuvio.app.core.automation.TheIntroDbSubmitProbe
import com.nuvio.app.core.automation.isTheIntroDbMutation
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.mdblist.retryAfterEpochMs
import com.nuvio.app.features.player.MarkDurationSlopSec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Query string for GET /v4/media. `duration_ms` is included only when the file length is known. */
internal fun theIntroDbMediaQuery(
    tmdbId: Int? = null,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    durationMs: Long? = null,
): String = buildList {
    if (tmdbId != null && tmdbId > 0) add("tmdb_id=$tmdbId")
    else imdbId?.takeIf { it.isNotBlank() }?.let { add("imdb_id=$it") }
    if (season != null && episode != null) {
        add("season=$season")
        add("episode=$episode")
    }
    if (durationMs != null && durationMs > 0) add("duration_ms=$durationMs")
}.joinToString("&")

internal object TheIntroDb {

    /** API v4 (2026-10-17): TVDB is the identity; tmdb_id/imdb_id still address a media. */
    const val API_BASE = "https://api.theintrodb.org/v4"
    const val MEDIA_URL = "$API_BASE/media"
    const val SUBMIT_URL = "$API_BASE/submit"
    const val MAP_URL = "$API_BASE/map"
    const val SUBMISSIONS_URL = "$API_BASE/submissions"
    private const val MAX_TVDB_PROBES = 4
    const val USER_STATS_URL = "$API_BASE/user/stats"
    const val PROVIDER = "theintrodb"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchTvIntervals(
        imdbId: String? = null,
        tmdbId: Int? = null,
        season: Int,
        episode: Int,
        durationMs: Long? = null,
    ): List<SkipInterval> {
        val data = getMedia(
            tmdbId = tmdbId,
            imdbId = imdbId,
            season = season,
            episode = episode,
            durationMs = durationMs,
        ) ?: return emptyList()
        return data.tvSkipIntervals()
    }

    suspend fun fetchMovieIntervals(
        imdbId: String? = null,
        tmdbId: Int? = null,
        durationMs: Long? = null,
    ): List<SkipInterval> {
        val data = getMedia(
            tmdbId = tmdbId,
            imdbId = imdbId,
            durationMs = durationMs,
        ) ?: return emptyList()
        return data.movieSkipIntervals()
    }

    suspend fun getMedia(
        tmdbId: Int? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        durationMs: Long? = null,
        apiKey: String? = null,
        nowEpochMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        transport: (suspend () -> RawHttpResponse)? = null,
    ): TheIntroDbMediaResponse? {
        lastPublicStatus = PublicMediaStatus.Unavailable
        lastPublicRetryUntilMs = null
        if (transport == null && AutomationTestMode.isActive) return null
        if (tmdbId == null && imdbId.isNullOrBlank()) return null
        val query = if (season == null && episode == null) {
            theIntroDbMovieQuery(tmdbId, imdbId, durationMs)
        } else {
            theIntroDbMediaQuery(tmdbId, imdbId, season, episode, durationMs)
        }
        val url = "$MEDIA_URL?$query"
        val key = if (AutomationTestMode.isActive) null else apiKey
        return try {
            val response = transport?.invoke() ?: httpRequestRaw(
                method = "GET",
                url = url,
                headers = if (key.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key"),
                body = "",
            )
            val errorField = runCatching {
                json.decodeFromString<TheIntroDbError>(response.body).error
            }.getOrNull()
            lastPublicStatus = classifyPublicMediaHttp(response.status, errorField)
            if (response.status == 429) {
                lastPublicRetryUntilMs = theIntroDbRateLimitUntilEpochMs(response.headers, nowEpochMs)
            }
            if (lastPublicStatus != PublicMediaStatus.Found) return null
            json.decodeFromString<TheIntroDbMediaResponse>(response.body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            lastPublicStatus = PublicMediaStatus.Unavailable
            null
        }
    }

    suspend fun verifyApiKey(apiKey: String): Boolean {
        if (AutomationTestMode.isActive) return true
        if (apiKey.isBlank()) return false
        return try {
            val response = httpRequestRaw(
                method = "GET",
                url = USER_STATS_URL,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Accept" to "application/json",
                ),
                body = "",
            )
            response.status in 200..299
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** Public GET /media for the last [getMedia] call. A 404 is [PublicMediaStatus.NoSubmissions]. */
    var lastPublicStatus: PublicMediaStatus = PublicMediaStatus.Unavailable
        private set

    /**
     * Epoch millis until a 429 may be retried. Null after any other response, including a
     * generic failure that happened to carry a retry header. The header value is not stored.
     */
    var lastPublicRetryUntilMs: Long? = null
        private set

    /** Why the last [listMySubmissions] call stopped early, or null when it went through. */
    var lastListError: String? = null
        private set

    /** Why the last [submitTimestamp] failed, or null when it went through or was already submitted. */
    var lastSubmitError: String? = null
        private set

    /** Epoch millis until which further submits are refused after HTTP 429. In memory only. */
    private var submitBlockedUntilEpochMs: Long = 0L

    internal fun resetSubmitGateForTest() {
        submitBlockedUntilEpochMs = 0L
        lastSubmitError = null
    }

    /**
     * This account's submissions: for one title when [tmdbId] is given (callers keep the rows of the
     * episode they need), or all of them. `/submissions` is not in the public v4 spec, so a refusal
     * is reported in [lastListError] rather than guessed around.
     */
    suspend fun listMySubmissions(
        apiKey: String,
        tmdbId: Int? = null,
        mediaType: String = "tv",
    ): List<TheIntroDbUserSubmission> {
        val rows = listPages(apiKey, tmdbId, mediaType)
        if (tmdbId == null) return rows
        // v4 rows name the show by its (series) tvdb_id only and the list ignores tmdb_id, so it can
        // hold every show. The title's tvdb_id comes from /media, which needs an episode that exists
        // for this title: try the episodes the rows name until one resolves.
        val tvdbRows = rows.filter { it.tmdbId == null && it.tvdbId != null }
        if (tvdbRows.isEmpty()) return submissionsOfTitle(rows, tmdbId, null)
        val titleTvdbId = tvdbRows.map { it.season to it.episode }.distinct().take(MAX_TVDB_PROBES)
            .firstNotNullOfOrNull { (season, episode) -> getMedia(tmdbId = tmdbId, season = season, episode = episode)?.tvdbId }
        if (titleTvdbId == null) lastListError = "Couldn't tell which of your submissions belong to this title"
        return submissionsOfTitle(rows, tmdbId, titleTvdbId)
    }

    private suspend fun listPages(apiKey: String, tmdbId: Int?, mediaType: String): List<TheIntroDbUserSubmission> {
        lastListError = null
        if (AutomationTestMode.isActive) return emptyList()
        if (apiKey.isBlank() || (tmdbId != null && tmdbId <= 0)) return emptyList()
        val filter = tmdbId?.let { "tmdb_id=$it&type=${if (mediaType.equals("movie", ignoreCase = true)) "movie" else "tv"}&" }.orEmpty()
        val collected = mutableListOf<TheIntroDbUserSubmission>()
        var offset = 0
        val pageSize = 100
        repeat(10) {
            val response = authorizedRequest("GET", "$SUBMISSIONS_URL?${filter}limit=$pageSize&offset=$offset", apiKey)
            if (response == null || response.status !in 200..299) {
                lastListError = errorMessage(response)
                return collected
            }
            if (response.body.isBlank()) return collected
            val page = runCatching { json.decodeFromString<TheIntroDbSubmissionsPage>(response.body) }.getOrNull()
            if (page == null) {
                lastListError = "TheIntroDB sent a list Nuvio can't read"
                return collected
            }
            collected += page.submissions
            val next = offset + page.submissions.size
            if (page.submissions.size < pageSize || (page.total > 0 && next >= page.total)) return collected
            offset = next
        }
        return collected
    }

    private suspend fun authorizedRequest(method: String, url: String, apiKey: String, body: String = ""): RawHttpResponse? {
        if (method == "POST" && isTheIntroDbMutation(url) && AutomationTestMode.isActive) {
            return RawHttpResponse(
                status = AutomationTestMode.submitStatus,
                statusText = "",
                url = url,
                body = "",
                headers = emptyMap(),
            )
        }
        return try {
            if (method == "POST" && isTheIntroDbMutation(url)) {
                TheIntroDbSubmitProbe.networkPosts += 1
            }
            httpRequestRaw(
                method = method,
                url = url,
                headers = buildMap {
                    put("Authorization", "Bearer $apiKey")
                    put("Accept", "application/json")
                    if (body.isNotEmpty()) put("Content-Type", "application/json")
                },
                body = body,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /** A plain message for a failed call: TheIntroDB's own `error` text when it sends one. */
    private fun errorMessage(response: RawHttpResponse?): String {
        if (response == null) return "Couldn't reach TheIntroDB"
        val reason = runCatching { json.decodeFromString<TheIntroDbError>(response.body).error }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        return when (response.status) {
            401, 403 -> "TheIntroDB refused the API key" + (reason?.let { ": $it" } ?: "")
            404 -> "TheIntroDB doesn't offer this (404)"
            429 -> TheIntroDbRateLimitMessage
            else -> "TheIntroDB error ${response.status}" + (reason?.let { ": $it" } ?: "")
        }
    }

    suspend fun submitTimestamp(
        apiKey: String,
        tmdbId: Int,
        imdbId: String? = null,
        type: String,
        segment: String,
        season: Int? = null,
        episode: Int? = null,
        startSec: Double?,
        endSec: Double?,
        videoDurationMs: Long? = null,
        tvdbEpisodeId: Long? = null,
        mapping: String? = null,
        nowEpochMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        transport: (suspend (method: String, url: String, apiKey: String, body: String) -> RawHttpResponse?)? = null,
    ): TheIntroDbSubmitResult {
        if (AutomationTestMode.isActive && transport == null) {
            // Automation: the stub in authorizedRequest answers; the key and body are never sent.
            val response = authorizedRequest("POST", SUBMIT_URL, apiKey, "")
            if (response != null && response.status in 200..299) {
                lastSubmitError = null
                return TheIntroDbSubmitResult.Accepted
            }
            return failSubmit(errorMessage(response))
        }
        if (apiKey.isBlank()) return failSubmit("")
        if (submitBlockedUntilEpochMs > nowEpochMs) return failSubmit(TheIntroDbRateLimitMessage)
        val body = theIntroDbSubmitPayload(
            tmdbId = tmdbId,
            imdbId = imdbId,
            type = type,
            segment = segment,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            videoDurationMs = videoDurationMs,
            tvdbEpisodeId = tvdbEpisodeId,
            mapping = mapping,
        ) ?: return failSubmit("")
        val response = try {
            if (transport != null) {
                transport("POST", SUBMIT_URL, apiKey, body)
            } else {
                authorizedRequest("POST", SUBMIT_URL, apiKey, body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return when {
            response != null && response.status in 200..299 -> {
                lastSubmitError = null
                TheIntroDbSubmitResult.Accepted
            }
            response?.status == 409 -> {
                lastSubmitError = null
                TheIntroDbSubmitResult.AlreadySubmitted
            }
            response?.status == 429 -> {
                submitBlockedUntilEpochMs = theIntroDbRateLimitUntilEpochMs(response.headers, nowEpochMs)
                failSubmit(TheIntroDbRateLimitMessage)
            }
            else -> failSubmit(errorMessage(response))
        }
    }

    private fun failSubmit(message: String): TheIntroDbSubmitResult.Failed {
        lastSubmitError = message.takeIf { it.isNotBlank() }
        return TheIntroDbSubmitResult.Failed(message)
    }
}

internal sealed interface TheIntroDbSubmitResult {
    data object Accepted : TheIntroDbSubmitResult
    data object AlreadySubmitted : TheIntroDbSubmitResult
    data class Failed(val message: String) : TheIntroDbSubmitResult
}

internal const val TheIntroDbAlreadySubmittedMessage = "Already submitted"
internal const val TheIntroDbRateLimitMessage = "TheIntroDB limit reached, try again later"
internal const val TheIntroDbMinVideoDurationMs = 300_000L
internal const val TheIntroDbMaxVideoDurationMs = 21_600_000L

/**
 * Retry-After is seconds (or an HTTP date). X-UsageLimit-Reset is an epoch second,
 * or a small delay in seconds. The later of the two wins, matching the MdbList client.
 */
internal fun theIntroDbRateLimitUntilEpochMs(headers: Map<String, String>, nowEpochMs: Long): Long {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
    val retryAt = header("Retry-After")?.let { retryAfterEpochMs(it, nowEpochMs) }
    val resetAt = header("X-UsageLimit-Reset")?.let { raw ->
        val number = raw.toLongOrNull()
        when {
            number == null -> retryAfterEpochMs(raw, nowEpochMs)
            number >= 1_000_000_000L -> number * 1_000L
            number >= 0L -> nowEpochMs + number * 1_000L
            else -> null
        }
    }
    return maxOf(nowEpochMs + 1_000L, retryAt ?: resetAt ?: (nowEpochMs + 60_000L))
}

/**
 * Body for `POST /v4/submit` (tmdb_id addresses the media; the server resolves the TVDB id).
 * An empty start on intro/recap means from 0. An empty end on credits/preview means through
 * the end of the file. Both empty (or 0 and 0) is the none vote and omits both time keys.
 * `video_duration_ms` is sent only inside 5 min..6 h.
 */
internal fun theIntroDbSubmitPayload(
    tmdbId: Int,
    imdbId: String? = null,
    type: String,
    segment: String,
    season: Int? = null,
    episode: Int? = null,
    startSec: Double? = null,
    endSec: Double? = null,
    videoDurationMs: Long? = null,
    tvdbEpisodeId: Long? = null,
    mapping: String? = null,
): String? {
    if (tmdbId <= 0) return null
    val normalizedType = if (type.equals("movie", ignoreCase = true)) "movie" else "tv"
    val normalizedSegment = when (segment.lowercase()) {
        "intro", "op", "mixed-op" -> "intro"
        "recap" -> "recap"
        "outro", "ed", "credits", "ending", "movie-credits" -> "credits"
        "preview" -> "preview"
        else -> return null
    }
    val noneVote = theIntroDbTimesAreNoneVote(startSec, endSec)
    val reportDuration = theIntroDbReportableDurationMs(videoDurationMs)
    return buildJsonObject {
        put("tmdb_id", tmdbId)
        if (!imdbId.isNullOrBlank()) put("imdb_id", imdbId)
        put("type", normalizedType)
        put("segment", normalizedSegment)
        if (normalizedType == "tv") {
            if (season != null) put("season", season)
            if (episode != null) put("episode", episode)
        }
        if (reportDuration != null) put("video_duration_ms", reportDuration)
        if (!noneVote) {
            if (!theIntroDbOmitStartMs(normalizedSegment, startSec) && startSec != null) {
                put("start_ms", (startSec * 1000.0).toLong())
            }
            if (!theIntroDbOmitEndMs(normalizedSegment, endSec, videoDurationMs) && endSec != null) {
                put("end_ms", theIntroDbEndMs(endSec, videoDurationMs))
            }
        }
        if (tvdbEpisodeId != null && tvdbEpisodeId > 0L) put("tvdb_episode_id", tvdbEpisodeId)
        if (mapping == "absolute") put("mapping", mapping)
    }.toString()
}

internal fun theIntroDbReportableDurationMs(videoDurationMs: Long?): Long? =
    videoDurationMs?.takeIf { it in TheIntroDbMinVideoDurationMs..TheIntroDbMaxVideoDurationMs }

/** Both clocks empty, including the 0/0 none chip, is "this media has none of this type". */
internal fun theIntroDbTimesAreNoneVote(startSec: Double?, endSec: Double?): Boolean =
    (startSec == null && endSec == null) || (startSec == 0.0 && endSec == 0.0)

private fun theIntroDbOmitStartMs(segment: String, startSec: Double?): Boolean {
    if (startSec == null) return true
    if (segment != "intro" && segment != "recap") return false
    return startSec <= 0.0
}

private fun theIntroDbOmitEndMs(segment: String, endSec: Double?, videoDurationMs: Long?): Boolean {
    if (endSec == null) return true
    if (segment != "credits" && segment != "preview") return false
    val durationMs = videoDurationMs?.takeIf { it > 0L } ?: return false
    val deltaSec = endSec - (durationMs / 1000.0)
    return deltaSec >= -0.2 && deltaSec <= MarkDurationSlopSec
}

internal data class TheIntroDbEpisodeFields(
    val tvdbEpisodeId: Long? = null,
    val mapping: String? = null,
)

/** Kitsu, MAL, and AniList ids number episodes absolutely. Anything else stays on TVDB default. */
internal fun theIntroDbNumberingIsAbsolute(videoId: String?): Boolean {
    val id = videoId.orEmpty()
    return id.startsWith("kitsu:") || id.startsWith("mal:") || id.startsWith("anilist:")
}

internal fun theIntroDbSimklSource(videoId: String?, imdbId: String?): Pair<String, String>? {
    val video = videoId.orEmpty()
    val prefixed = listOf("kitsu", "mal", "anilist").firstOrNull { video.startsWith("$it:") }
    if (prefixed != null) {
        val id = video.removePrefix("$prefixed:").substringBefore(':').trim()
        if (id.isNotEmpty()) return prefixed to id
    }
    val imdb = imdbId?.trim().orEmpty()
    if (imdb.startsWith("tt", ignoreCase = true)) return "imdb" to imdb
    return null
}

internal fun theIntroDbEpisodeFields(
    videoId: String?,
    isMovie: Boolean,
    season: Int?,
    episode: Int?,
    mappings: List<SimklIdResolver.EpisodeMapping>,
): TheIntroDbEpisodeFields {
    if (isMovie) return TheIntroDbEpisodeFields()
    val absolute = theIntroDbNumberingIsAbsolute(videoId)
    val matched = mappings.firstOrNull { row ->
        season != null && episode != null && row.tvdbSeason == season && row.tvdbEpisode == episode
    } ?: if (absolute && episode != null && episode > 0) {
        mappings.firstOrNull { row -> row.animeEpisode == episode }
    } else {
        null
    }
    return TheIntroDbEpisodeFields(
        tvdbEpisodeId = matched?.tvdbEpisodeId?.takeIf { it > 0L },
        mapping = if (absolute) "absolute" else null,
    )
}

/**
 * End at the stream length is valid. A rounding hair past that length is sent
 * as the length so the body is not past the media. A real overshoot is left
 * for the strip to reject.
 */
internal fun theIntroDbEndMs(endSec: Double, videoDurationMs: Long?): Long {
    val raw = (endSec * 1000.0).toLong()
    val duration = videoDurationMs?.takeIf { it > 0L } ?: return raw
    if (raw <= duration) return raw
    val overSec = (raw - duration) / 1000.0
    return if (overSec <= MarkDurationSlopSec) duration else raw
}

@Serializable
data class TheIntroDbMediaResponse(
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("intro") val intro: List<TheIntroDbSegment>? = null,
    @SerialName("recap") val recap: List<TheIntroDbSegment>? = null,
    @SerialName("credits") val credits: List<TheIntroDbSegment>? = null,
    @SerialName("preview") val preview: List<TheIntroDbSegment>? = null,
)

@Serializable
data class TheIntroDbSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("submission_count") val submissionCount: Int? = null,
)

internal fun TheIntroDbMediaResponse.tvSkipIntervals(): List<SkipInterval> = buildList {
    addAll(intro.toTheIntroDbIntervals("intro"))
    addAll(recap.toTheIntroDbIntervals("recap"))
    addAll(credits.toTheIntroDbIntervals("outro"))
    addAll(preview.toTheIntroDbIntervals("preview"))
}

internal fun TheIntroDbMediaResponse.movieSkipIntervals(): List<SkipInterval> {
    val creditIntervals = credits.toTheIntroDbIntervals("movie-credits")
    val firstCredits = creditIntervals.firstOrNull()
    val laterCredits = creditIntervals.drop(1).map { it.copy(type = "post-credits") }
    val introIntervals = intro.toTheIntroDbIntervals("intro")
    // Every segment type TheIntroDB has gets its own Skip button, movies included.
    return listOfNotNull(firstCredits) + laterCredits + introIntervals +
        recap.toTheIntroDbIntervals("recap") + preview.toTheIntroDbIntervals("preview")
}

@Serializable
internal data class TheIntroDbSubmissionsPage(
    val submissions: List<TheIntroDbUserSubmission> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

@Serializable
internal data class TheIntroDbUserSubmission(
    val id: String? = null,
    /** v3 rows only; v4 rows carry [tvdbId] instead. */
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
    val type: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val segment: String? = null,
    val status: String? = null,
    /** Times, when the list returns them (null start: from 0, null end: to the end). */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.json.JsonNames("start_ms", "startMs") val startMs: Long? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.json.JsonNames("end_ms", "endMs") val endMs: Long? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.json.JsonNames("video_duration_ms", "videoDurationMs") val videoDurationMs: Long? = null,
)

/**
 * Rows of one title: same tmdb_id, or same tvdb_id. A row that cannot be proven to belong to the
 * title (unknown tvdb id, or naming neither) is dropped: the list can hold every show's rows, and
 * keeping them put another show's times on this show's episode with the same number.
 */
internal fun submissionsOfTitle(
    rows: List<TheIntroDbUserSubmission>,
    tmdbId: Int,
    tvdbId: Int?,
): List<TheIntroDbUserSubmission> = rows.filter { row ->
    when {
        row.tmdbId != null -> row.tmdbId == tmdbId
        row.tvdbId != null -> tvdbId != null && row.tvdbId == tvdbId
        else -> false
    }
}

/** [listedForTmdbId]: the title the list was asked for (v4 rows have no tmdb_id). */
internal fun TheIntroDbUserSubmission.toFlagRecord(listedForTmdbId: Int): FlagSubmissionRecord? {
    val id = tmdbId ?: listedForTmdbId
    val segmentName = segment?.takeIf { it.isNotBlank() } ?: return null
    return FlagSubmissionRecord(
        tmdbId = id,
        type = type.orEmpty(),
        season = season,
        episode = episode,
        segment = segmentName,
        status = status.orEmpty(),
        submissionId = this.id.orEmpty(),
        startMs = startMs,
        endMs = endMs,
    )
}

private fun List<TheIntroDbSegment>?.toTheIntroDbIntervals(type: String): List<SkipInterval> {
    if (this.isNullOrEmpty()) return emptyList()
    return mapNotNull { segment ->
        val start = (segment.startMs ?: 0L) / 1000.0
        val end = segment.endMs?.div(1000.0) ?: return@mapNotNull null
        if (!start.isFinite() || !end.isFinite() || start < 0.0 || end <= start) null
        else SkipInterval(start, end, type, TheIntroDb.PROVIDER)
    }
}

@Serializable
private data class TheIntroDbError(val error: String? = null)
