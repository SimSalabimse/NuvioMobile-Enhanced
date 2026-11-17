package com.nuvio.app.features.downloads

/** One episode row as the sheet's enable and source-change decisions see it. */
internal data class SeasonSheetRow(
    val key: String,
    val confirmed: Boolean,
    val anchor: Boolean,
    val existingDownload: Boolean,
    val overridden: Boolean,
    val userChecked: Boolean,
    val matching: Boolean,
    /** Set when this episode already has a resolved source. Null while a lookup has cleared it. */
    val sourceId: String?,
    val unresolved: Boolean,
)

/** What a season-wide or per-episode source change does to one row. */
internal enum class SeasonRowSourcePlan {
    /** Leave the episode's source alone. */
    Keep,
    /** This episode downloads the source the user just picked. */
    AdoptPicked,
    /** Look up the season source for this episode. */
    Resolve,
    /** Drop any source. The episode is not part of this download. */
    Clear,
}

/**
 * Season-wide Change (the provider row, and the season quality chips).
 * A per-episode manual pick ([SeasonSheetRow.overridden]) stays. Episodes still on the season
 * default are resolved again. The anchor adopts the new source immediately.
 */
internal fun seasonWideSourcePlan(row: SeasonSheetRow): SeasonRowSourcePlan = when {
    row.existingDownload -> SeasonRowSourcePlan.Clear
    row.overridden -> SeasonRowSourcePlan.Keep
    !row.confirmed -> SeasonRowSourcePlan.Clear
    row.anchor -> SeasonRowSourcePlan.AdoptPicked
    else -> SeasonRowSourcePlan.Resolve
}

/** One episode's own Change. Every other row stays as it is. */
internal fun episodeSourcePlan(row: SeasonSheetRow, episodeKey: String): SeasonRowSourcePlan =
    if (row.key == episodeKey) SeasonRowSourcePlan.AdoptPicked else SeasonRowSourcePlan.Keep

internal fun applySeasonSourceRows(rows: List<SeasonSheetRow>, seasonSourceId: String): List<SeasonSheetRow> =
    rows.map { row ->
        when (seasonWideSourcePlan(row)) {
            SeasonRowSourcePlan.Keep -> row.copy(matching = false)
            SeasonRowSourcePlan.AdoptPicked -> row.copy(
                sourceId = seasonSourceId,
                matching = false,
                unresolved = false,
            )
            SeasonRowSourcePlan.Resolve -> row.copy(
                sourceId = null,
                matching = true,
                unresolved = false,
            )
            SeasonRowSourcePlan.Clear -> row.copy(sourceId = null, matching = false, unresolved = false)
        }
    }

internal fun applyEpisodeSourceRow(
    rows: List<SeasonSheetRow>,
    episodeKey: String,
    sourceId: String,
): List<SeasonSheetRow> =
    rows.map { row ->
        when (episodeSourcePlan(row, episodeKey)) {
            SeasonRowSourcePlan.AdoptPicked -> row.copy(
                sourceId = sourceId,
                matching = false,
                unresolved = false,
                overridden = true,
                userChecked = true,
            )
            else -> row
        }
    }

/** Closing the source picker without choosing does not start a lookup and does not change rows. */
internal fun rowsAfterSourcePickerCancel(rows: List<SeasonSheetRow>): List<SeasonSheetRow> = rows

/** A finished lookup. Rows that were not being resolved, including manual picks, stay put. */
internal fun finishSeasonResolution(
    rows: List<SeasonSheetRow>,
    resolvedSourceIds: Map<String, String?>,
): List<SeasonSheetRow> = rows.map { row ->
    if (!row.matching || row.overridden) row.copy(matching = false)
    else {
        val sourceId = resolvedSourceIds[row.key]
        if (sourceId.isNullOrBlank()) {
            row.copy(matching = false, sourceId = null, unresolved = true)
        } else {
            row.copy(matching = false, sourceId = sourceId, unresolved = false)
        }
    }
}

/**
 * The lookup failed, timed out, or was abandoned. Matching flags are cleared so Download cannot
 * stay disabled. The anchor (or any row that already adopted a source) remains downloadable.
 */
internal fun failSeasonResolution(rows: List<SeasonSheetRow>): List<SeasonSheetRow> =
    rows.map { row ->
        if (row.matching && !row.overridden) {
            row.copy(matching = false, sourceId = null, unresolved = true)
        } else {
            row.copy(matching = false)
        }
    }

internal data class SeasonDownloadButtonState(
    val enabled: Boolean,
    val loading: Boolean,
)

/**
 * Download is enabled when the selection is confirmed, nothing is mid-enqueue, at least one
 * checked episode has a source, and no checked episode is still waiting on a lookup that has not
 * produced a source. A stale matching flag on a row that already has a source does not disable it.
 * While a lookup or the enqueue itself is running, [loading] is set so the control is not a dead button.
 */
internal fun seasonDownloadButtonState(
    selectionConfirmed: Boolean,
    queuing: Boolean,
    rows: List<SeasonSheetRow>,
): SeasonDownloadButtonState {
    val selected = rows.filter { it.confirmed && it.userChecked && !it.existingDownload }
    val waitingOnLookup = selected.any { it.matching && it.sourceId == null }
    val ready = selected.any { it.sourceId != null && !it.unresolved }
    val enabled = selectionConfirmed && !queuing && !waitingOnLookup && ready
    val loading = selectionConfirmed && !enabled && (queuing || selected.any { it.matching })
    return SeasonDownloadButtonState(enabled = enabled, loading = loading)
}

/** True only when auto-play chose the source. A manual Change or quality tap is not automatic. */
internal fun seasonSourcePickIsAutomatic(autoPicked: Boolean): Boolean = autoPicked

internal enum class SeasonDownloadNotice {
    /** At least one episode was queued. Show only the success toast. */
    Queued,
    /** Nothing new was queued because the episodes are already in Downloads. */
    AlreadyInDownloads,
    /** Nothing was queued and enqueue reported a failure. */
    Failed,
    /** Nothing was queued and there is no failure text. */
    None,
}

/**
 * What to tell the user after enqueue.
 * A partial success (some queued, some already held or failed) is [Queued] so the sheet does not
 * also surface "Already in Downloads" while it closes.
 */
internal fun seasonDownloadNotice(
    queued: Int,
    failureMessage: String?,
    alreadyInDownloads: Int,
): SeasonDownloadNotice = when {
    queued > 0 -> SeasonDownloadNotice.Queued
    !failureMessage.isNullOrBlank() -> SeasonDownloadNotice.Failed
    alreadyInDownloads > 0 -> SeasonDownloadNotice.AlreadyInDownloads
    else -> SeasonDownloadNotice.None
}

/** Slide duration for leaving the sheet. Reduce motion is instant: no slide. */
internal const val SEASON_SHEET_DISMISS_MS = 300

internal fun seasonSheetDismissDurationMs(reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else SEASON_SHEET_DISMISS_MS
