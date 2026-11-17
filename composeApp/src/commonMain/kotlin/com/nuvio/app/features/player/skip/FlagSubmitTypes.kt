package com.nuvio.app.features.player.skip

internal fun canonicalFlagSegmentType(raw: String?): String? {
    return when (raw?.trim()?.lowercase()) {
        "intro", "op", "mixed-op", "opening" -> "intro"
        "recap" -> "recap"
        "credits", "outro", "ed", "mixed-ed", "ending", "movie-credits" -> "outro"
        "preview" -> "preview"
        else -> null
    }
}

/** Stable id for one movie or episode. Empty when there is no IMDb id to remember. */
internal fun flagSubmitContentKey(
    imdbId: String,
    season: Int,
    episode: Int,
    isMovie: Boolean,
): String {
    val id = imdbId.trim().lowercase()
    if (!id.startsWith("tt")) return ""
    return if (isMovie) "$id:movie" else "$id:$season:$episode"
}

internal data class FlagSubmissionRecord(
    val tmdbId: Int,
    val type: String,
    val season: Int?,
    val episode: Int?,
    val segment: String,
    val status: String,
    val submissionId: String = "",
    val startMs: Long? = null,
    val endMs: Long? = null,
)

/**
 * Types this account already sent for this title. Rejected submissions stay available
 * so they can be sent again. Community skip data is not an input.
 */
internal fun submittedFlagTypesForTitle(
    records: List<FlagSubmissionRecord>,
    tmdbId: Int,
    isMovie: Boolean,
    season: Int,
    episode: Int,
): Set<String> = flagTypesForTitle(records, tmdbId, isMovie, season, episode, rejected = false)

internal enum class ListStatusBucket {
    Confirmed,
    Pending,
    Rejected,
}

/**
 * Review words from this account's list.
 * A 200 leaves the row pending, including a list status of "submitted".
 * Approved, published, and a duplicate (the 409 case) are confirmed.
 * Blank and unknown words stay pending. They are not a filled check.
 */
internal fun listStatusBucket(status: String): ListStatusBucket = when (status.trim().lowercase()) {
    "rejected", "declined", "denied" -> ListStatusBucket.Rejected
    "approved", "accepted", "active", "duplicate", "confirmed", "published" -> ListStatusBucket.Confirmed
    else -> ListStatusBucket.Pending
}

internal data class OwnListBuckets(
    val confirmed: Set<String> = emptySet(),
    val pending: Set<String> = emptySet(),
    val rejected: Set<String> = emptySet(),
    val confirmedRows: List<AccountSegment> = emptyList(),
    val pendingRows: List<AccountSegment> = emptyList(),
)

/** This account's rows for one title, split by review status. Community data is not an input. */
internal fun ownListBucketsForTitle(
    records: List<FlagSubmissionRecord>,
    tmdbId: Int,
    isMovie: Boolean,
    season: Int,
    episode: Int,
): OwnListBuckets {
    if (tmdbId <= 0) return OwnListBuckets()
    val confirmed = linkedSetOf<String>()
    val pending = linkedSetOf<String>()
    val rejected = linkedSetOf<String>()
    val confirmedRows = mutableListOf<AccountSegment>()
    val pendingRows = mutableListOf<AccountSegment>()
    records.forEach { record ->
        if (record.tmdbId != tmdbId) return@forEach
        val movie = record.type.equals("movie", ignoreCase = true)
        if (movie != isMovie) return@forEach
        if (!isMovie && (record.season != season || record.episode != episode)) return@forEach
        val type = canonicalFlagSegmentType(record.segment) ?: return@forEach
        val segment = AccountSegment(
            id = record.submissionId.ifBlank { nextSubmissionId(type, confirmedRows + pendingRows) },
            type = type,
            startMs = record.startMs,
            endMs = record.endMs,
        )
        when (listStatusBucket(record.status)) {
            ListStatusBucket.Confirmed -> {
                confirmed += type
                confirmedRows += segment
            }
            ListStatusBucket.Pending -> {
                pending += type
                pendingRows += segment
            }
            ListStatusBucket.Rejected -> rejected += type
        }
    }
    val surviving = (confirmedRows.map { it.type } + pendingRows.map { it.type }).toSet()
    return OwnListBuckets(
        confirmed = confirmed,
        pending = pending - confirmed,
        rejected = rejected.filter { it !in surviving }.toSet(),
        confirmedRows = confirmedRows,
        pendingRows = pendingRows.filter { row -> confirmedRows.none { it.id == row.id } },
    )
}

/** Rejected rows for this title. They must not draw a check. */
internal fun rejectedFlagTypesForTitle(
    records: List<FlagSubmissionRecord>,
    tmdbId: Int,
    isMovie: Boolean,
    season: Int,
    episode: Int,
): Set<String> = flagTypesForTitle(records, tmdbId, isMovie, season, episode, rejected = true)

private fun flagTypesForTitle(
    records: List<FlagSubmissionRecord>,
    tmdbId: Int,
    isMovie: Boolean,
    season: Int,
    episode: Int,
    rejected: Boolean,
): Set<String> {
    if (tmdbId <= 0) return emptySet()
    return records.mapNotNullTo(linkedSetOf()) { record ->
        if (record.tmdbId != tmdbId) return@mapNotNullTo null
        val isRejected = listStatusBucket(record.status) == ListStatusBucket.Rejected
        if (isRejected != rejected) return@mapNotNullTo null
        val movie = record.type.equals("movie", ignoreCase = true)
        if (movie != isMovie) return@mapNotNullTo null
        if (!isMovie && (record.season != season || record.episode != episode)) return@mapNotNullTo null
        canonicalFlagSegmentType(record.segment)
    }
}

/**
 * Checks on the segment tabs. A loaded submission list wins over the ledger.
 * A type submitted in this session still counts until that list says it was rejected.
 */
internal fun flagChecksForTitle(
    ledgerTypes: Set<String>,
    sessionTypes: Set<String>,
    remoteTypes: Set<String>?,
    rejectedTypes: Set<String> = emptySet(),
): Set<String> {
    val rejected = rejectedTypes.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) }
    val base = (remoteTypes ?: ledgerTypes) + sessionTypes
    return base.mapNotNullTo(linkedSetOf()) { raw ->
        val type = canonicalFlagSegmentType(raw) ?: return@mapNotNullTo null
        type.takeUnless { it in rejected }
    }
}

/**
 * One episode's saved types become [remoteTypes].
 * An empty remote set removes that key. Other keys stay.
 */
internal fun replaceFlagSubmitLedgerTypes(
    entries: Map<String, Set<String>>,
    contentKey: String,
    remoteTypes: Set<String>,
): Map<String, Set<String>> {
    if (contentKey.isEmpty()) return entries
    val canonical = remoteTypes.mapNotNullTo(linkedSetOf()) { canonicalFlagSegmentType(it) }
    val next = entries.toMutableMap()
    if (canonical.isEmpty()) next.remove(contentKey) else next[contentKey] = canonical
    return next
}

internal fun encodeFlagSubmitSegments(entries: Map<String, List<String>>): String {
    return entries.entries
        .sortedBy { it.key }
        .mapNotNull { (key, types) ->
            val cleanKey = key.trim()
            if (cleanKey.isEmpty() || cleanKey.contains('\n') || cleanKey.contains('=')) return@mapNotNull null
            val cleanTypes = types.mapNotNull { canonicalFlagSegmentType(it) }
            if (cleanTypes.isEmpty()) return@mapNotNull null
            "$cleanKey=${cleanTypes.joinToString(",")}"
        }
        .joinToString("\n")
}

internal fun decodeFlagSubmitSegments(raw: String): Map<String, List<String>> {
    if (raw.isBlank()) return emptyMap()
    val out = linkedMapOf<String, List<String>>()
    raw.lineSequence().forEach { line ->
        val parts = line.split('=', limit = 2)
        if (parts.size != 2) return@forEach
        val key = parts[0].trim()
        if (key.isEmpty()) return@forEach
        val types = parts[1].split(',').mapNotNull { canonicalFlagSegmentType(it.trim()) }
        if (types.isNotEmpty()) out[key] = types
    }
    return out
}

internal fun encodeFlagSubmitLedger(entries: Map<String, Set<String>>): String {
    return entries.entries
        .sortedBy { it.key }
        .mapNotNull { (key, types) ->
            val cleanKey = key.trim()
            if (cleanKey.isEmpty() || cleanKey.contains('\n') || cleanKey.contains('=')) return@mapNotNull null
            val cleanTypes = types.mapNotNull { canonicalFlagSegmentType(it) }.distinct().sorted()
            if (cleanTypes.isEmpty()) return@mapNotNull null
            "$cleanKey=${cleanTypes.joinToString(",")}"
        }
        .joinToString("\n")
}

internal fun decodeFlagSubmitLedger(raw: String): Map<String, Set<String>> {
    if (raw.isBlank()) return emptyMap()
    val out = linkedMapOf<String, Set<String>>()
    raw.lineSequence().forEach { line ->
        val parts = line.split('=', limit = 2)
        if (parts.size != 2) return@forEach
        val key = parts[0].trim()
        if (key.isEmpty()) return@forEach
        val types = parts[1].split(',').mapNotNull { canonicalFlagSegmentType(it) }.toSet()
        if (types.isNotEmpty()) out[key] = types
    }
    return out
}
