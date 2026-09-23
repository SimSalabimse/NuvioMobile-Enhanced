package com.nuvio.app.features.player.skip

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object TheIntroDb {

    const val API_BASE = "https://api.theintrodb.org/v3"
    const val MEDIA_URL = "$API_BASE/media"
    const val SUBMIT_URL = "$API_BASE/submit"
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
    ): TheIntroDbMediaResponse? {
        if (tmdbId == null && imdbId.isNullOrBlank()) return null
        val params = buildList {
            if (tmdbId != null && tmdbId > 0) add("tmdb_id=$tmdbId")
            else imdbId?.takeIf { it.isNotBlank() }?.let { add("imdb_id=$it") }
            if (season != null && episode != null) {
                add("season=$season")
                add("episode=$episode")
            }
            if (durationMs != null && durationMs > 0) add("duration_ms=$durationMs")
        }
        val url = "$MEDIA_URL?${params.joinToString("&")}"
        return try {
            val text = if (apiKey.isNullOrBlank()) {
                httpGetText(url)
            } else {
                httpRequestRaw(
                    method = "GET",
                    url = url,
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                    body = "",
                ).body
            }
            json.decodeFromString<TheIntroDbMediaResponse>(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun verifyApiKey(apiKey: String): Boolean {
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
    ): Boolean {
        if (apiKey.isBlank() || tmdbId <= 0) return false
        val normalizedType = if (type.equals("movie", ignoreCase = true)) "movie" else "tv"
        val normalizedSegment = when (segment.lowercase()) {
            "intro", "op", "mixed-op" -> "intro"
            "recap" -> "recap"
            "outro", "ed", "credits", "ending", "movie-credits" -> "credits"
            "preview" -> "preview"
            else -> return false
        }
        val body = buildJsonObject {
            put("tmdb_id", tmdbId)
            if (!imdbId.isNullOrBlank()) put("imdb_id", imdbId)
            put("type", normalizedType)
            put("segment", normalizedSegment)
            if (normalizedType == "tv") {
                if (season != null) put("season", season)
                if (episode != null) put("episode", episode)
            }
            if (videoDurationMs != null && videoDurationMs > 0) {
                put("video_duration_ms", videoDurationMs)
            }
            if (startSec != null) put("start_ms", (startSec * 1000.0).toLong())
            if (endSec != null) put("end_ms", (endSec * 1000.0).toLong())
        }.toString()
        return try {
            val response = httpRequestRaw(
                method = "POST",
                url = SUBMIT_URL,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                ),
                body = body,
            )
            response.status == 200 || response.status == 201
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}

@Serializable
data class TheIntroDbMediaResponse(
    @SerialName("tmdb_id") val tmdbId: Int? = null,
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
    return listOfNotNull(firstCredits) + laterCredits + introIntervals
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
