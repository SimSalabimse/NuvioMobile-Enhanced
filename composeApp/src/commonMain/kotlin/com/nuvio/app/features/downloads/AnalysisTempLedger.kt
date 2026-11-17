package com.nuvio.app.features.downloads

/**
 * Download ids this feature created so they can be deleted after analysis.
 * A download the user already had, or one the user started, is never in [taggedIds].
 */
internal data class AnalysisTempLedger(
    val taggedIds: Set<String> = emptySet(),
)

/**
 * [createdByFeature] tags [downloadId] only when the feature created that row.
 * [userAlreadyOwned] refuses the tag, including when both flags are set.
 * [replacedId] is the row a new download replaced. That tag is cleared.
 */
internal fun AnalysisTempLedger.withAcquisition(
    downloadId: String,
    createdByFeature: Boolean,
    userAlreadyOwned: Boolean,
    replacedId: String? = null,
): AnalysisTempLedger {
    val cleared = taggedIds - setOfNotNull(replacedId?.takeIf { it.isNotBlank() })
    if (userAlreadyOwned || !createdByFeature) {
        return copy(taggedIds = if (downloadId.isBlank()) cleared else cleared - downloadId)
    }
    if (downloadId.isBlank()) return copy(taggedIds = cleared)
    return copy(taggedIds = cleared + downloadId)
}

/** Ids from [candidates] that this feature is allowed to delete. */
internal fun AnalysisTempLedger.idsToDelete(candidates: Set<String>): Set<String> =
    candidates intersect taggedIds

internal data class AnalysisDownloadChoice(
    val contentKey: String,
    val existingDownloadId: String?,
    val existingTagged: Boolean,
)

internal data class AnalysisDownloadPlan(
    val reuseUntagged: Set<String>,
    val alreadyTagged: Set<String>,
    val needsDownload: Set<String>,
)

/**
 * An existing untagged row is the user's. It is reused and not tagged.
 * An existing tagged row stays tagged and is not downloaded again.
 * Only a missing row is created by the feature.
 */
internal fun planAnalysisDownloads(choices: List<AnalysisDownloadChoice>): AnalysisDownloadPlan {
    val reuse = linkedSetOf<String>()
    val tagged = linkedSetOf<String>()
    val need = linkedSetOf<String>()
    choices.forEach { choice ->
        val id = choice.existingDownloadId
        when {
            id != null && choice.existingTagged -> tagged += id
            id != null -> reuse += id
            else -> need += choice.contentKey
        }
    }
    return AnalysisDownloadPlan(reuse, tagged, need)
}

/**
 * A tagged file is not "one the user has", so a later download may replace it.
 * The replace clears the tag. An untagged finished, running, queued, or paused file stays.
 */
internal fun leaveExistingDownloadInPlace(status: DownloadStatus?, analysisTemp: Boolean): Boolean {
    if (analysisTemp) return false
    return seasonExistingDownload(status) != null
}

internal fun encodeAnalysisTempLedger(ids: Set<String>): String =
    ids.map { it.trim() }.filter { it.isNotEmpty() && !it.contains('\n') }.sorted().joinToString("\n")

internal fun decodeAnalysisTempLedger(raw: String): Set<String> {
    if (raw.isBlank()) return emptySet()
    return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
