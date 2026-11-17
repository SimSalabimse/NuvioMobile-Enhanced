package com.nuvio.app.features.player.skip

/** Public media lookup. A 404 is an empty catalogue, not a failure of this account. */
internal enum class PublicMediaStatus {
    Found,
    NoSubmissions,
    Unavailable,
}

internal fun classifyPublicMediaHttp(status: Int, errorField: String?): PublicMediaStatus = when {
    status == 404 || errorField.equals("no_submissions", ignoreCase = true) -> PublicMediaStatus.NoSubmissions
    status in 200..299 -> PublicMediaStatus.Found
    else -> PublicMediaStatus.Unavailable
}

internal data class PublicSegmentFact(
    val available: Boolean,
    /** Sum of `submission_count` when the API sent it. Null when the field is absent. */
    val count: Int?,
)

internal fun publicSegmentFact(segments: List<TheIntroDbSegment>?): PublicSegmentFact {
    val rows = segments.orEmpty()
    val counts = rows.mapNotNull { it.submissionCount }
    val available = rows.any { segment ->
        val end = segment.endMs
        end != null && end > (segment.startMs ?: 0L)
    }
    return PublicSegmentFact(
        available = available,
        count = counts.takeIf { it.isNotEmpty() }?.sum(),
    )
}

/**
 * One movie segment.
 * [ownConfirmed] is this account's confirmed submission only. A public range never sets it.
 * [detected] is an on-device range and is not a submission.
 */
internal data class MovieSegmentRow(
    val type: String,
    val publicAvailable: Boolean,
    val publicCount: Int?,
    val ownConfirmed: Boolean,
    val ownPending: Boolean,
    val detected: Boolean,
)

internal val MOVIE_SEGMENT_TYPES: List<String> = listOf("intro", "recap", "outro", "preview")

/** The catalogue block on a detail page. It is in the column, not drawn over the metadata. */
internal const val MOVIE_SEGMENT_STATUS_TAG = "movie-segment-status"

/** True when no type has a public range, a count, or anything from this account or this device. */
internal fun movieSegmentCatalogueIsEmpty(rows: List<MovieSegmentRow>): Boolean = rows.all { row ->
    !row.publicAvailable &&
        row.publicCount == null &&
        !row.ownConfirmed &&
        !row.ownPending &&
        !row.detected
}

internal fun movieSegmentRows(
    media: TheIntroDbMediaResponse?,
    publicStatus: PublicMediaStatus,
    confirmed: Set<String>,
    pending: Set<String>,
    detected: Set<String>,
): List<MovieSegmentRow> {
    val facts = if (publicStatus == PublicMediaStatus.Found && media != null) {
        mapOf(
            "intro" to publicSegmentFact(media.intro),
            "recap" to publicSegmentFact(media.recap),
            "outro" to publicSegmentFact(media.credits),
            "preview" to publicSegmentFact(media.preview),
        )
    } else {
        emptyMap()
    }
    val ownConfirmed = confirmed.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) }
    val ownPending = pending.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) } - ownConfirmed
    val local = detected.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) } - ownConfirmed - ownPending
    return MOVIE_SEGMENT_TYPES.map { type ->
        val fact = facts[type]
        MovieSegmentRow(
            type = type,
            publicAvailable = fact?.available == true,
            publicCount = fact?.count,
            ownConfirmed = type in ownConfirmed,
            ownPending = type in ownPending,
            detected = type in local,
        )
    }
}

/**
 * Screenshot rows: a public count, a confirmed check, a pending send, and a local detection.
 * The count is not a check.
 */
internal fun movieSegmentFixtureRows(): List<MovieSegmentRow> = listOf(
    MovieSegmentRow(
        type = "intro",
        publicAvailable = true,
        publicCount = 12,
        ownConfirmed = false,
        ownPending = false,
        detected = false,
    ),
    MovieSegmentRow(
        type = "recap",
        publicAvailable = true,
        publicCount = null,
        ownConfirmed = true,
        ownPending = false,
        detected = false,
    ),
    MovieSegmentRow(
        type = "outro",
        publicAvailable = false,
        publicCount = null,
        ownConfirmed = false,
        ownPending = true,
        detected = false,
    ),
    MovieSegmentRow(
        type = "preview",
        publicAvailable = false,
        publicCount = null,
        ownConfirmed = false,
        ownPending = false,
        detected = true,
    ),
)

/** Movie GET /media. Season and episode are never sent. */
internal fun theIntroDbMovieQuery(
    tmdbId: Int? = null,
    imdbId: String? = null,
    durationMs: Long? = null,
): String = theIntroDbMediaQuery(
    tmdbId = tmdbId,
    imdbId = imdbId,
    season = null,
    episode = null,
    durationMs = durationMs,
)
