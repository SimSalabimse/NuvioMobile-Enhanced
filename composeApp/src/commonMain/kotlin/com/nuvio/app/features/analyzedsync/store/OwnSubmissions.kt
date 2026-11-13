package com.nuvio.app.features.analyzedsync.store

import com.nuvio.app.features.analyzedsync.clock.Hlc
import com.nuvio.app.features.analyzedsync.crypto.encodeHex
import com.nuvio.app.features.analyzedsync.crypto.sha256
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.record.SubmissionRecord
import com.nuvio.app.features.analyzedsync.record.episodeKey
import com.nuvio.app.features.analyzedsync.record.isShowId
import com.nuvio.app.features.analyzedsync.session.AnalyzedSyncVault
import com.nuvio.app.features.analyzedsync.session.decodeSession
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.player.skip.TheIntroDbUserSubmission
import com.nuvio.app.features.streams.epochMs

private val REMOTE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
private val STATUS = Regex("^[A-Za-z0-9 ._-]{1,32}$")

/**
 * Remembers this account's TheIntroDB submissions under the SHA-256 of the API key.
 * The key itself is not stored or logged. An inferred missing segment is never written as a mark.
 */
internal fun noteOwnSubmissions(apiKey: String, rows: List<TheIntroDbUserSubmission>, showId: String?) {
    if (apiKey.isEmpty()) return
    val scope = encodeHex(sha256(apiKey.encodeToByteArray()))
    val deviceId = AnalyzedSyncVault.readSession(ProfileRepository.activeProfileId)
        ?.let(::decodeSession)?.deviceId ?: "phone"
    val now = epochMs()
    val fresh = rows.mapNotNull { row -> submissionRecord(scope, row, showId, deviceId, now) }
        .distinctBy { it.id }
    val port = analysisSyncPort()
    val existing = port.readLocal().map { it.record }
    val kept = existing.filter { it !is SubmissionRecord || it.accountScope != scope }
    val previous = existing.filterIsInstance<SubmissionRecord>().filter { it.accountScope == scope }
        .map { canonicalJson(it.toWire()) }.toSet()
    val next = fresh.map { canonicalJson(it.toWire()) }.toSet()
    if (previous == next) return
    port.replaceRecords(kept + fresh, now)
}

private fun submissionRecord(
    scope: String,
    row: TheIntroDbUserSubmission,
    fallbackShowId: String?,
    deviceId: String,
    now: Long,
): SubmissionRecord? {
    val remote = row.id ?: return null
    if (!REMOTE_ID.matches(remote)) return null
    val show = row.tmdbId?.let { "tmdb:$it" }?.takeIf(::isShowId) ?: fallbackShowId?.takeIf(::isShowId) ?: return null
    val segment = when (row.segment?.lowercase()) {
        "intro" -> "intro"
        "recap" -> "recap"
        "credits", "outro" -> "credits"
        "preview" -> "preview"
        else -> return null
    }
    val movie = row.type.equals("movie", ignoreCase = true)
    val season = if (movie) null else row.season?.toLong()
    val episode = if (movie) null else row.episode?.toLong()
    if (movie) {
        if (row.season != null || row.episode != null) return null
    } else if (episodeKey(season, episode) == null) {
        return null
    }
    val start = row.startMs ?: 0L
    val end = row.endMs ?: row.videoDurationMs ?: start
    val duration = row.videoDurationMs ?: 0L
    if (start < 0 || duration < 0 || end < start) return null
    val status = row.status?.takeIf { STATUS.matches(it) } ?: "pending"
    return SubmissionRecord(
        id = "submission/$scope/$remote",
        hlc = Hlc(now, 0, deviceId),
        accountScope = scope,
        remoteId = remote,
        showId = show,
        mediaType = if (movie) "movie" else "tv",
        season = season,
        episode = episode,
        segmentType = segment,
        status = status,
        startMs = start,
        endMs = end,
        durationMs = duration,
    )
}
