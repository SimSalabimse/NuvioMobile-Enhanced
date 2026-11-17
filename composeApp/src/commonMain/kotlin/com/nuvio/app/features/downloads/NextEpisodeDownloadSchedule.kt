package com.nuvio.app.features.downloads

import com.nuvio.app.features.watching.domain.isShortPlaceholderDuration

/**
 * Share of the next episode's size added on top of the transfer time, so the file is done
 * when the current episode ends even if the link runs a bit slower than the average.
 */
internal const val NEXT_EPISODE_DOWNLOAD_SAFETY_MARGIN = 0.20

/**
 * Bytes per second used until a real download has been measured.
 * 1 MB/s is deliberately slow, so the first episodes start early instead of late.
 */
internal const val CONSERVATIVE_DEFAULT_DOWNLOAD_BYTES_PER_SECOND = 1_000_000.0

/** Stand-in size when the next episode has no known size and no bitrate. Same 2 GB guess as other downloads. */
internal const val NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES = GUESSED_EPISODE_BYTES

/** How often a playing episode is asked whether the next download should start. */
internal const val NEXT_EPISODE_DOWNLOAD_SAMPLE_INTERVAL_MS = 2_000L

internal enum class NextEpisodeSizeBasis {
    KnownBytes,
    BitrateTimesDuration,
    UnknownFallback,
}

internal data class NextEpisodeExpectedSize(
    val bytes: Long,
    val basis: NextEpisodeSizeBasis,
)

/**
 * Known file size wins. Otherwise bitrate (bits/s) times duration. Otherwise [NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES].
 */
internal fun nextEpisodeExpectedBytes(
    knownBytes: Long?,
    bitrateBitsPerSecond: Double?,
    durationMs: Long?,
): NextEpisodeExpectedSize {
    knownBytes?.takeIf { it > 0L }?.let { return NextEpisodeExpectedSize(it, NextEpisodeSizeBasis.KnownBytes) }
    val bits = bitrateBitsPerSecond?.takeIf { it.isFinite() && it > 0.0 }
    val duration = durationMs?.takeIf { it > 0L }
    if (bits != null && duration != null) {
        val bytes = (bits / 8.0 * (duration / 1000.0)).toLong()
        if (bytes > 0L) return NextEpisodeExpectedSize(bytes, NextEpisodeSizeBasis.BitrateTimesDuration)
    }
    return NextEpisodeExpectedSize(NEXT_EPISODE_SIZE_WHEN_UNKNOWN_BYTES, NextEpisodeSizeBasis.UnknownFallback)
}

internal enum class NextEpisodeDownloadSkip {
    NoNextEpisode,
    NotAired,
    AlreadyDownloaded,
    AlreadyQueued,
    NotOnWifi,
    LowDataOrExpensive,
    ManualDownloadActive,
    NotEnoughSpace,
    RuntimeUnknown,
}

internal sealed class NextEpisodeDownloadDecision {
    data object StartNow : NextEpisodeDownloadDecision()
    data class StartAt(val positionMs: Long) : NextEpisodeDownloadDecision()
    data class Skip(val reason: NextEpisodeDownloadSkip) : NextEpisodeDownloadDecision()
}

/**
 * Facts for one look at "should the next episode download start". No UI state.
 * [freeBytes] null means the platform could not read the disk (the start is allowed, as with other downloads).
 * [measuredDownloadBytesPerSecond] null means no sample yet ([CONSERVATIVE_DEFAULT_DOWNLOAD_BYTES_PER_SECOND]).
 */
internal data class NextEpisodeDownloadFacts(
    val durationMs: Long,
    val positionMs: Long,
    val playbackSpeed: Double = 1.0,
    val hasNextEpisode: Boolean = true,
    val nextHasAired: Boolean = true,
    val knownSizeBytes: Long? = null,
    val bitrateBitsPerSecond: Double? = null,
    val nextEpisodeDurationMs: Long? = null,
    val measuredDownloadBytesPerSecond: Double? = null,
    val onWifi: Boolean = true,
    val lowDataOrExpensive: Boolean = false,
    val freeBytes: Long? = null,
    val manualDownloadActiveOrQueued: Boolean = false,
    val nextAlreadyDownloaded: Boolean = false,
    val nextAlreadyQueued: Boolean = false,
    val safetyMargin: Double = NEXT_EPISODE_DOWNLOAD_SAFETY_MARGIN,
)

/**
 * When to start the next episode so the file finishes as the current one ends.
 * [NextEpisodeDownloadDecision.StartNow] includes the play press when the file cannot finish in time
 * even if the transfer began at the start of the episode.
 */
internal fun decideNextEpisodeDownload(facts: NextEpisodeDownloadFacts): NextEpisodeDownloadDecision {
    if (!facts.hasNextEpisode) return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.NoNextEpisode)
    if (!facts.nextHasAired) return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.NotAired)
    if (facts.nextAlreadyDownloaded) return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.AlreadyDownloaded)
    if (facts.nextAlreadyQueued) return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.AlreadyQueued)
    if (!facts.onWifi) return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.NotOnWifi)
    if (facts.lowDataOrExpensive) return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.LowDataOrExpensive)
    if (facts.manualDownloadActiveOrQueued) return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.ManualDownloadActive)

    val expected = nextEpisodeExpectedBytes(facts.knownSizeBytes, facts.bitrateBitsPerSecond, facts.nextEpisodeDurationMs)
    val free = facts.freeBytes
    if (free != null && free < expected.bytes + downloadSpaceReserve(expected.bytes)) {
        return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.NotEnoughSpace)
    }
    if (facts.durationMs <= 0L || isShortPlaceholderDuration(facts.durationMs)) {
        return NextEpisodeDownloadDecision.Skip(NextEpisodeDownloadSkip.RuntimeUnknown)
    }

    val speed = facts.measuredDownloadBytesPerSecond?.takeIf { it.isFinite() && it > 0.0 }
        ?: CONSERVATIVE_DEFAULT_DOWNLOAD_BYTES_PER_SECOND
    val margin = facts.safetyMargin.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: NEXT_EPISODE_DOWNLOAD_SAFETY_MARGIN
    val playback = facts.playbackSpeed.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val downloadMs = (expected.bytes.toDouble() * (1.0 + margin) / speed * 1000.0).toLong()
    val fullPlayMs = (facts.durationMs / playback).toLong()
    if (downloadMs >= fullPlayMs) return NextEpisodeDownloadDecision.StartNow
    val startAt = facts.durationMs - (downloadMs * playback).toLong()
    val position = facts.positionMs.coerceAtLeast(0L)
    return if (position >= startAt) {
        NextEpisodeDownloadDecision.StartNow
    } else {
        NextEpisodeDownloadDecision.StartAt(startAt)
    }
}
