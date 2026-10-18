package com.nuvio.app.features.downloads

import androidx.compose.runtime.compositionLocalOf
import com.nuvio.app.features.analysis.MediaAnalysisStore
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.parseRuntimeMinutes

/** Something wrong with a finished download's file; re-downloading fetches it again. */
internal data class DownloadProblem(val message: String)

/** Files under this share of the expected size are cut off. */
private const val TRUNCATED_SIZE_RATIO = 0.97

/** A probed length under this share of the episode's runtime means a damaged file. */
private const val SHORT_DURATION_RATIO = 0.7

/**
 * Health of one finished file: smaller on disk than the size the server announced (cut off), or a
 * probed length well under the listed runtime (damaged).
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
    val sizes = runCatching { DownloadDiskInfo.listDownloadFiles() }.getOrDefault(emptyList())
        .associate { it.name to it.sizeBytes }
    return finished.mapNotNull { item ->
        val probed = MediaAnalysisStore.load(MediaAnalysisStore.keyFor(item))
            ?.takeIf { it.fileName == item.fileName } // measured on this very file
            ?.durationMs
        downloadProblem(
            expectedBytes = item.totalBytes,
            actualBytes = sizes[item.fileName],
            probedDurationMs = probed,
            expectedDurationMs = item.listedRuntimeMs(),
        )?.let { item.id to it }
    }.toMap()
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
