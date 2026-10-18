package com.nuvio.app.features.downloads

import androidx.compose.runtime.compositionLocalOf
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.parseRuntimeMinutes

/** Something wrong with a finished download's file; re-downloading fetches it again. */
internal data class DownloadProblem(val message: String)

/** Files under this share of the expected size are cut off. */
private const val TRUNCATED_SIZE_RATIO = 0.97

/** Files over this share of the expected size had other bytes glued on. */
private const val OVERSIZED_RATIO = 1.01

/** A probed length under this share of the episode's runtime means a damaged file. */
private const val SHORT_DURATION_RATIO = 0.7

/**
 * Health of one finished file: smaller on disk than the size the server announced (cut off) or
 * larger (other bytes appended), or a probed length well under the listed runtime (damaged).
 * Unknown figures give no verdict.
 */
internal fun downloadProblem(
    expectedBytes: Long?,
    actualBytes: Long?,
    probedDurationMs: Long?,
    expectedDurationMs: Long?,
): DownloadProblem? {
    if (actualBytes != null && expectedBytes != null && expectedBytes > 0L &&
        actualBytes < expectedBytes * TRUNCATED_SIZE_RATIO
    ) {
        return DownloadProblem(
            "File incomplete: ${formatDownloadBytes(actualBytes)} of ${formatDownloadBytes(expectedBytes)}"
        )
    }
    if (actualBytes != null && expectedBytes != null && expectedBytes > 0L &&
        actualBytes > expectedBytes * OVERSIZED_RATIO
    ) {
        return DownloadProblem(
            "File damaged: ${formatDownloadBytes(actualBytes)} instead of ${formatDownloadBytes(expectedBytes)}"
        )
    }
    if (probedDurationMs != null && probedDurationMs > 0L && expectedDurationMs != null && expectedDurationMs > 0L &&
        probedDurationMs < expectedDurationMs * SHORT_DURATION_RATIO
    ) {
        return DownloadProblem(
            "File may be damaged: plays ${probedDurationMs / 60_000} of ${expectedDurationMs / 60_000} min"
        )
    }
    return null
}

/** Problems of the finished downloads by id: file sizes on disk, analysed lengths, cached runtimes. */
internal fun downloadProblems(items: List<DownloadItem>): Map<String, DownloadProblem> {
    val finished = items.filter { it.status == DownloadStatus.Completed }
    if (finished.isEmpty()) return emptyMap()
    val sizes = downloadFileSizes()
    return finished.mapNotNull { item -> item.healthProblem(sizes)?.let { item.id to it } }.toMap()
}

internal fun downloadFileSizes(): Map<String, Long> =
    runCatching { DownloadDiskInfo.listDownloadFiles() }.getOrDefault(emptyList()).associate { it.name to it.sizeBytes }

/** One finished file against [expectedBytes] (what the server announced) and its analysed length. */
internal fun DownloadItem.healthProblem(sizes: Map<String, Long>, expectedBytes: Long? = totalBytes): DownloadProblem? {
    val probed = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(this))
        ?.takeIf { it.fileName == fileName } // measured on this very file
        ?.durationMs
    return downloadProblem(
        expectedBytes = expectedBytes,
        actualBytes = sizes[fileName],
        probedDurationMs = probed,
        expectedDurationMs = listedRuntimeMs(),
    )
}

private fun DownloadItem.listedRuntimeMs(): Long? {
    val meta = MetaDetailsRepository.peek(parentMetaType, parentMetaId) ?: return null
    val minutes = if (isEpisode) {
        meta.videos.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber }?.runtime
    } else {
        parseRuntimeMinutes(meta.runtime)
    }
    return minutes?.takeIf { it > 0 }?.let { it * 60_000L }
}

/** Health problems shown on the Downloads rows (provided by the Downloads screen). */
internal val LocalDownloadProblems = compositionLocalOf<Map<String, DownloadProblem>> { emptyMap() }
