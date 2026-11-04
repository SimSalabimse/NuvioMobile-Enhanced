package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.requestAnalysisForMarkedDownloads
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted ids of downloads the season overview created for analysis.
 * Deletion always goes through [DownloadsRepository.cancelDownloads].
 */
internal object AnalysisTempDownloads {
    private const val STORE_KEY = "analysis_temp_download_ids_v1"
    private val lock = SynchronizedObject()
    private var cached: AnalysisTempLedger? = null
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun taggedIds(): Set<String> = ledger().taggedIds

    fun isTagged(id: String): Boolean = id.isNotBlank() && id in taggedIds()

    fun tagCreated(id: String) {
        if (id.isBlank()) return
        update { it.withAcquisition(id, createdByFeature = true, userAlreadyOwned = false) }
    }

    /** A download replaced this row. The user's (or smart download's) new file is not tagged. */
    fun noteReplaced(oldId: String) {
        if (oldId.isBlank()) return
        update { it.withAcquisition("", createdByFeature = false, userAlreadyOwned = true, replacedId = oldId) }
    }

    fun onDownloadFinished(id: String) {
        if (!isTagged(id)) return
        val started = requestAnalysisForMarkedDownloads(listOf(id))
        if (!started) deleteTagged(setOf(id), "analysis-temp-unavailable")
    }

    fun onDownloadFailed(id: String) {
        if (!isTagged(id)) return
        deleteTagged(setOf(id), "analysis-temp-failed")
    }

    fun onAnalysisSettled(id: String) {
        deleteTagged(setOf(id), "analysis-temp-finished")
    }

    fun cancelSession(sessionIds: Set<String>) {
        deleteTagged(sessionIds, "analysis-temp-cancelled")
    }

    /** Next launch: delete tagged rows that are still on disk. Untagged rows stay. */
    fun sweep() {
        val present = DownloadsRepository.uiState.value.items.map { it.id }.toSet()
        val doomed = ledger().idsToDelete(present)
        val stale = taggedIds() - present
        if (stale.isNotEmpty()) update { AnalysisTempLedger(it.taggedIds intersect present) }
        if (doomed.isEmpty()) return
        DownloadsRepository.cancelDownloads(doomed.toList(), "analysis-temp-sweep")
        val still = DownloadsRepository.uiState.value.items.map { it.id }.toSet()
        update { AnalysisTempLedger((it.taggedIds - doomed) + (doomed intersect still)) }
    }

    private fun deleteTagged(candidates: Set<String>, reason: String) {
        val doomed = ledger().idsToDelete(candidates)
        if (doomed.isEmpty()) return
        DownloadsRepository.cancelDownloads(doomed.toList(), reason)
        val still = DownloadsRepository.uiState.value.items.map { it.id }.toSet()
        update { AnalysisTempLedger((it.taggedIds - doomed) + (doomed intersect still)) }
    }

    private fun ledger(): AnalysisTempLedger = synchronized(lock) {
        cached ?: AnalysisTempLedger(decodeAnalysisTempLedger(DownloadsSettingsStorage.loadString(STORE_KEY).orEmpty()))
            .also { cached = it }
    }

    private fun update(transform: (AnalysisTempLedger) -> AnalysisTempLedger) {
        synchronized(lock) {
            val next = transform(ledger())
            if (next == cached) return
            cached = next
            val encoded = encodeAnalysisTempLedger(next.taggedIds)
            DownloadsSettingsStorage.saveString(STORE_KEY, encoded.ifBlank { null })
        }
        _revision.value += 1
    }
}

internal data class AnalysisDownloadOutcome(
    val taggedIds: Set<String>,
    val reusedIds: Set<String>,
    val unqueued: Int,
)

/**
 * Downloads [videos] that are not already on the device, tags only the rows this call creates,
 * and asks analysis to run. Rows the user already had are reused and left untagged.
 */
internal suspend fun downloadEpisodesForAnalysis(
    meta: MetaDetails,
    videos: List<MetaVideo>,
): AnalysisDownloadOutcome {
    DownloadsRepository.ensureLoaded()
    val items = DownloadsRepository.uiState.value.items
    val tagged = AnalysisTempDownloads.taggedIds()
    val plan = planAnalysisDownloads(
        videos.map { video ->
            val key = downloadLogicalContentKey(meta.id, video.season, video.episode)
            val existing = items.firstOrNull { it.logicalContentKey == key }
            AnalysisDownloadChoice(
                contentKey = key,
                existingDownloadId = existing?.id,
                existingTagged = existing?.id?.let { it in tagged } == true,
            )
        },
    )
    val needVideos = videos.filter { video ->
        downloadLogicalContentKey(meta.id, video.season, video.episode) in plan.needsDownload
    }
    val settings = currentAutoPickSettings(meta.id)
    val names = installedAddonNames()
    val quality = SmartDownloadSettings.snapshot().quality
    val rows = needVideos.mapNotNull { video ->
        val streams = SeasonStreamCatalog.load(
            meta.type,
            seasonStreamVideoId(meta.id, video),
            video.season,
            video.episode,
            annotateDebridCache = true,
        )
        val stream = pickWithoutReference(streams.orderedForQuality(quality), settings, names) ?: return@mapNotNull null
        SeasonEnqueueRow(video, stream)
    }
    val before = DownloadsRepository.uiState.value.items.associateBy { it.logicalContentKey }
    if (rows.isNotEmpty()) {
        enqueueSeasonDownloads(meta, rows, startedByUser = false)
    }
    val created = mutableSetOf<String>()
    val after = DownloadsRepository.uiState.value.items
    needVideos.forEach { video ->
        val key = downloadLogicalContentKey(meta.id, video.season, video.episode)
        val now = after.firstOrNull { it.logicalContentKey == key } ?: return@forEach
        if (before[key] == null) {
            AnalysisTempDownloads.tagCreated(now.id)
            created += now.id
        }
    }
    val ready = (plan.reuseUntagged + plan.alreadyTagged).filter { id ->
        items.any { it.id == id && it.status == DownloadStatus.Completed }
    }
    requestAnalysisForMarkedDownloads(ready)
    return AnalysisDownloadOutcome(
        taggedIds = created + plan.alreadyTagged,
        reusedIds = plan.reuseUntagged,
        unqueued = needVideos.size - rows.size,
    )
}
