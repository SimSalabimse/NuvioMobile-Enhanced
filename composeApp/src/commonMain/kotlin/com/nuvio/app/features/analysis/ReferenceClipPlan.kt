package com.nuvio.app.features.analysis

/**
 * How a reference fingerprint was obtained.
 * A cache hit did not decode. A joined session still decodes its own window.
 */
internal enum class ReferenceReadKind {
    CacheHit,
    Decoded,
    JoinedSession,
}

internal enum class ReferenceChoice {
    Local,
    Remote,
    None,
}

internal data class ReferencePlan(
    val choice: ReferenceChoice,
    val episode: Int? = null,
    val reason: String,
)

internal data class ReferenceFingerprintKey(
    val identity: String,
    val startMs: Long,
    val endMs: Long,
    val algorithm: String,
)

internal const val REFERENCE_FINGERPRINT_ALGORITHM = "haitsma-kalker-v1"
internal const val REFERENCE_MAX_ATTEMPTS = 2

/**
 * Completed local siblings first. The episode being analysed is not a sibling.
 * Remote episodes stay eligible when the only local file is the one already in hand.
 * [reason] is set when neither source has another episode.
 */
internal fun planReference(
    targetEpisode: Int,
    localOtherEpisodes: List<Int>,
    remoteOtherEpisodes: List<Int>,
): ReferencePlan {
    val local = localOtherEpisodes.filter { it != targetEpisode }.distinct()
    if (local.isNotEmpty()) {
        val episode = local.minBy { kotlin.math.abs(it - targetEpisode) }
        return ReferencePlan(ReferenceChoice.Local, episode, "local-file")
    }
    val remote = remoteOtherEpisodes.filter { it != targetEpisode }.distinct()
    if (remote.isNotEmpty()) {
        val episode = remote.minBy { kotlin.math.abs(it - targetEpisode) }
        return ReferencePlan(ReferenceChoice.Remote, episode, "remote-sibling")
    }
    return ReferencePlan(ReferenceChoice.None, null, "no-eligible-local-or-remote-sibling")
}

/** File name or catalogue identity. Playback URLs and headers are not part of the key. */
internal fun referenceIdentity(localFileName: String?, imdbId: String, season: Int, episode: Int): String {
    val file = localFileName?.trim().orEmpty()
    if (file.isNotEmpty()) return "file:$file"
    return "release:${imdbId.trim().lowercase()}:$season:$episode"
}

/** One stored window serves only that same identity, window, and algorithm. */
internal fun ReferenceFingerprintKey.serves(requested: ReferenceFingerprintKey): Boolean = this == requested

internal class ReferenceFingerprintCache {
    private val values = mutableMapOf<ReferenceFingerprintKey, String>()
    private val openIdentities = mutableSetOf<String>()

    fun lookup(key: ReferenceFingerprintKey): Pair<String, ReferenceReadKind>? {
        val hit = values[key] ?: return null
        return hit to ReferenceReadKind.CacheHit
    }

    /**
     * Opens or joins a decode session for [identity].
     * Joining does not return another window's fingerprint.
     */
    fun beginDecode(identity: String): ReferenceReadKind {
        val kind = if (identity in openIdentities) ReferenceReadKind.JoinedSession else ReferenceReadKind.Decoded
        openIdentities += identity
        return kind
    }

    fun store(key: ReferenceFingerprintKey, fingerprint: String) {
        values[key] = fingerprint
        openIdentities -= key.identity
    }

    fun endDecode(identity: String) {
        openIdentities -= identity
    }

    fun clear() {
        values.clear()
        openIdentities.clear()
    }
}

internal data class ReferenceWriteGuard(
    val fileName: String?,
    val analyzedAtMs: Long,
    val derivedVersion: Int,
    val userSegmentStamp: String,
)

internal fun referenceUserSegmentStamp(segments: Map<String, UserSegment>): String =
    segments.entries
        .sortedBy { it.key }
        .joinToString(";") { (key, mark) ->
            "$key:${mark.startMs}:${mark.endMs}:${mark.markedAtMs}:${mark.durationMs}"
        }

internal fun referenceWriteGuard(result: MediaAnalysisResult): ReferenceWriteGuard = ReferenceWriteGuard(
    fileName = result.fileName,
    analyzedAtMs = result.analyzedAtMs,
    derivedVersion = result.derivedVersion,
    userSegmentStamp = referenceUserSegmentStamp(result.userSegments),
)

/**
 * Copies intro and credits from a finished reference read.
 * A changed file, a new analysis, a changed user mark, or a cancel drops the write.
 * User marks on the current result are kept as they are.
 */
internal fun mergeReferenceWrite(
    current: MediaAnalysisResult,
    proposed: MediaAnalysisResult,
    guard: ReferenceWriteGuard,
    cancelled: Boolean,
): MediaAnalysisResult? {
    if (cancelled) return null
    if (current.fileName != guard.fileName) return null
    if (current.analyzedAtMs != guard.analyzedAtMs) return null
    if (current.derivedVersion != guard.derivedVersion) return null
    if (referenceUserSegmentStamp(current.userSegments) != guard.userSegmentStamp) return null
    var next = current
    if ("intro" !in current.userSegments && current.introEndMs == null && proposed.introEndMs != null) {
        next = next.copy(
            introStartMs = proposed.introStartMs,
            introEndMs = proposed.introEndMs,
            foundUsing = next.foundUsing + proposed.foundUsing.filterKeys { it == "intro" },
        )
    }
    if ("outro" !in current.userSegments && current.creditsStartMs == null && proposed.creditsStartMs != null) {
        next = next.copy(
            creditsStartMs = proposed.creditsStartMs,
            creditsEndMs = proposed.creditsEndMs,
            foundUsing = next.foundUsing + proposed.foundUsing.filterKeys { it == "outro" },
        )
    }
    return next
}

internal fun referenceActivityToken(operation: String, seconds: Int, season: Int, episode: Int): String {
    val op = when (operation) {
        "read", "compare" -> operation
        else -> "fetch"
    }
    return "ref:$op:${seconds.coerceAtLeast(0)}:${season.coerceAtLeast(0)}:${episode.coerceAtLeast(0)}"
}

internal data class ReferenceActivity(
    val operation: String,
    val seconds: Int,
    val season: Int,
    val episode: Int,
)

internal fun parseReferenceActivity(token: String): ReferenceActivity? {
    val parts = token.split(":")
    if (parts.size != 5 || parts[0] != "ref") return null
    val operation = parts[1]
    if (operation != "read" && operation != "fetch" && operation != "compare") return null
    val seconds = parts[2].toIntOrNull() ?: return null
    val season = parts[3].toIntOrNull() ?: return null
    val episode = parts[4].toIntOrNull() ?: return null
    return ReferenceActivity(operation, seconds, season, episode)
}

/**
 * Status line for a reference step.
 * [ReferenceActivity.operation] `read` is a downloaded episode. `compare` is the match itself.
 */
internal fun referenceActivitySentence(activity: ReferenceActivity): String {
    val place = "S${activity.season} E${activity.episode}"
    return when (activity.operation) {
        "read" -> "Reading the downloaded episode, ${activity.seconds}s of $place"
        "compare" -> "Comparing ${activity.seconds}s of $place"
        else -> "Fetching ${activity.seconds}s of $place"
    }
}

/** Local analysis ranks ahead of a reference read so one clip cannot hold the queue. */
internal fun analysisQueueRank(needsLocalWork: Boolean, needsReference: Boolean): Int = when {
    needsLocalWork -> 0
    needsReference -> 1
    else -> 2
}
