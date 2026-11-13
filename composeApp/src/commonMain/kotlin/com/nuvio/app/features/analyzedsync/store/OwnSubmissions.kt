package com.nuvio.app.features.analyzedsync.store

import com.nuvio.app.features.analyzedsync.clock.Hlc
import com.nuvio.app.features.analyzedsync.crypto.encodeHex
import com.nuvio.app.features.analyzedsync.crypto.sha256
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.record.SubmissionRecord
import com.nuvio.app.features.analyzedsync.record.canonicalShowId
import com.nuvio.app.features.analyzedsync.record.episodeKey
import com.nuvio.app.features.analyzedsync.session.AnalyzedSyncVault
import com.nuvio.app.features.analyzedsync.session.decodeSession
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.player.skip.TheIntroDbUserSubmission
import com.nuvio.app.features.streams.epochMs

/** SHA-256 of the trimmed UTF-8 key. Empty after trim has no scope. Trim matches `String.prototype.trim`. */
internal fun accountScope(apiKey: String): String? {
    val trimmed = trimAccountKey(apiKey)
    if (trimmed.isEmpty()) return null
    return encodeHex(sha256(trimmed.encodeToByteArray()))
}

private fun trimAccountKey(value: String): String {
    var start = 0
    var end = value.length
    while (start < end && isTrimWhitespace(value[start])) start++
    while (end > start && isTrimWhitespace(value[end - 1])) end--
    return value.substring(start, end)
}

private fun isTrimWhitespace(ch: Char): Boolean = when (ch) {
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
    else -> ch in '\u2000'..'\u200A'
}

private val REMOTE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
private val STATUS = Regex("^[A-Za-z0-9 ._-]{1,32}$")

/**
 * Remembers this account's TheIntroDB submissions under the SHA-256 of the API key.
 * The key itself is not stored or logged. An inferred missing segment is never written as a mark.
 */
internal fun noteOwnSubmissions(apiKey: String, rows: List<TheIntroDbUserSubmission>, showId: String?) {
    val scope = accountScope(apiKey) ?: return
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
    val show = row.tmdbId?.let { canonicalShowId("tmdb:$it") } ?: fallbackShowId?.let(::canonicalShowId) ?: return null
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
