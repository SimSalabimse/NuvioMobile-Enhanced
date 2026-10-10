package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private val partialPrefixCache = mutableMapOf<String, ByteArray>()
    private val startQueue = DownloadStartQueue()
    private val persistGate = DownloadPersistGate()
    private var thermalObserverStarted = false
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        ensureThermalObserver()
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        partialPrefixCache.clear()
        startQueue.clear()
        persistGate.reset()
        hasLoaded = false
        _hasUnseenCompleted.value = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        return matchingPlayableDownload(
            items = _uiState.value.items,
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            videoId = videoId,
            playable = { it.hasPlayableLocalFile() },
        )
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status == DownloadStatus.Completed) {
            val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = item.localFileUri,
                destinationFileName = item.fileName,
            )
            if (resolvedUri != null) {
                if (resolvedUri != item.localFileUri) {
                    mutateItem(item.id) { current ->
                        if (current.fileName == item.fileName) {
                            current.copy(
                                localFileUri = resolvedUri,
                                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            )
                        } else {
                            current
                        }
                    }
                }
                return resolvedUri
            }
        }
        if (item.status != DownloadStatus.Completed && !item.earlyPlayReady) return null
        if (item.status == DownloadStatus.Failed) return null
        DownloadsPlatformDownloader.updatePartialTarget(
            downloadId = item.id,
            fileName = item.fileName,
            totalBytes = item.totalBytes,
            downloadRunning = item.status == DownloadStatus.Downloading,
        )
        return DownloadsPlatformDownloader.partialPlaybackUrl(item.id)
    }

    fun onPartialPlaybackClosed(downloadId: String) {
        ensureLoaded()
        val renamedUri = DownloadsPlatformDownloader.finishPartialPlayback(downloadId) ?: return
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Completed,
                localFileUri = renamedUri,
                earlyPlayReady = false,
                playsWhenDownloadFinishes = false,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
    ): DownloadEnqueueResult {
        ensureLoaded()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            partialPrefixCache.remove(existing.id)
            startQueue.discard(existing.id)
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val toStart = startQueue.request(downloadId, DownloadThermalGate.blocksNextFile())
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            sourceUrl = sourceUrl,
            downloadId = downloadId,
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = addonSubtitleRequests(contentType, videoId),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            status = if (downloadId in toStart) DownloadStatus.Downloading else DownloadStatus.Queued,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persistStatus()
        applyStarts(toStart)

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    fun pauseDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return

        activeHandles.remove(downloadId)?.cancel()
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
            )
        }
        val paused = _uiState.value.items.firstOrNull { it.id == downloadId } ?: item
        DownloadsPlatformDownloader.updatePartialTarget(
            downloadId = downloadId,
            fileName = paused.fileName,
            totalBytes = paused.totalBytes,
            downloadRunning = false,
        )
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach(::pauseDownload)
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused && item.status != DownloadStatus.Failed) return

        val toStart = startQueue.request(
            item.id,
            DownloadThermalGate.blocksNextFile(),
            atFront = true,
        )
        val reset = item.copy(
            status = if (item.id in toStart) DownloadStatus.Downloading else DownloadStatus.Queued,
            errorMessage = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persistStatus()
        applyStarts(toStart)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    internal fun reattachBackgroundDownload(downloadId: String) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeHandles.remove(downloadId)?.cancel()
        startQueue.discard(downloadId)
        val restored = DownloadsPlatformDownloader.restoreItem(item)
        if (restored.status != DownloadStatus.Downloading) {
            replaceItem(restored)
            persistStatus()
            return
        }
        val toStart = startQueue.request(
            downloadId,
            DownloadThermalGate.blocksNextFile(),
            atFront = true,
        )
        val published = if (downloadId in toStart) {
            restored
        } else {
            restored.copy(status = DownloadStatus.Queued, updatedAtEpochMs = DownloadsClock.nowEpochMs())
        }
        replaceItem(published)
        persistStatus()
        applyStarts(toStart)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeHandles.remove(downloadId)?.cancel()
        partialPrefixCache.remove(downloadId)
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persistStatus()
        applyStarts(startQueue.release(downloadId, DownloadThermalGate.blocksNextFile()))
    }

    private fun loadFromDisk() {
        ensureThermalObserver()
        _hasUnseenCompleted.value = false
        hasLoaded = true
        startQueue.clear()
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                val statusNormalized = DownloadsPlatformDownloader.restoreItem(item)

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                val earlyPlayNormalized = refreshStoredEarlyPlay(localUriNormalized)
                if (earlyPlayNormalized != item) {
                    shouldPersistNormalized = true
                }
                earlyPlayNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persistStatus()
        }
        adoptLoadedItems()
    }

    private fun startDownload(item: DownloadItem) {
        val request = DownloadPlatformRequest(item)

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = onProgress@{ downloadedBytes, totalBytes ->
                val current = _uiState.value.items.firstOrNull { it.id == item.id } ?: return@onProgress
                if (current.status != DownloadStatus.Downloading) return@onProgress
                val safeBytes = downloadedBytes.coerceAtLeast(0L)
                val resolvedTotal = totalBytes?.takeIf { it > 0L } ?: current.totalBytes
                val decision = decideEarlyPlay(current.id, current.fileName, safeBytes)
                DownloadsPlatformDownloader.updatePartialTarget(
                    downloadId = current.id,
                    fileName = current.fileName,
                    totalBytes = resolvedTotal,
                    downloadRunning = true,
                )
                mutateItem(item.id) { latest ->
                    if (latest.status != DownloadStatus.Downloading) {
                        latest
                    } else {
                        latest.copy(
                            downloadedBytes = safeBytes,
                            totalBytes = resolvedTotal,
                            earlyPlayReady = decision.playable,
                            playsWhenDownloadFinishes = decision.playsWhenDownloadFinishes,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            errorMessage = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                partialPrefixCache.remove(item.id)
                val current = _uiState.value.items.firstOrNull { it.id == item.id }
                if (current != null) {
                    DownloadsPlatformDownloader.updatePartialTarget(
                        downloadId = current.id,
                        fileName = current.fileName,
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        downloadRunning = false,
                    )
                }
                mutateItem(item.id) { latest ->
                    if (latest.status != DownloadStatus.Downloading) return@mutateItem latest
                    latest.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            latest.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: latest.totalBytes,
                        earlyPlayReady = false,
                        playsWhenDownloadFinishes = false,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
            },
            onFailure = { message ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
                DownloadsPlatformDownloader.updatePartialTarget(
                    downloadId = item.id,
                    fileName = item.fileName,
                    totalBytes = item.totalBytes,
                    downloadRunning = false,
                )
            },
            onPaused = {
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(status = DownloadStatus.Paused, errorMessage = null)
                }
            },
        )

        activeHandles[item.id] = handle
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        val before = _uiState.value.items.firstOrNull { it.id == downloadId }
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id != downloadId) {
                item
            } else {
                val next = transform(item)
                if (next != item) changed = true
                next
            }
        }

        if (!changed) return
        publish(updated)
        val after = updated.firstOrNull { it.id == downloadId }
        val statusChanged = before?.status != after?.status
        val progressTick = before != null && after != null &&
            before.status == DownloadStatus.Downloading &&
            after.status == DownloadStatus.Downloading
        if (statusChanged || !progressTick) {
            persistStatus()
        } else {
            persistProgressIfDue()
        }
        if (statusChanged && before?.status == DownloadStatus.Downloading && after?.status != DownloadStatus.Downloading) {
            applyStarts(startQueue.release(downloadId, DownloadThermalGate.blocksNextFile()))
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        val previousStatuses = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && previousStatuses[it.id].let { status -> status != null && status != DownloadStatus.Completed } }) {
            _hasUnseenCompleted.value = true
        }
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encodeItems(_uiState.value.items),
        )
    }

    private fun persistStatus() {
        persistGate.recordStatusWrite(DownloadsClock.nowEpochMs())
        persist()
    }

    private fun persistProgressIfDue() {
        if (persistGate.allowProgressWrite(DownloadsClock.nowEpochMs())) persist()
    }

    private fun ensureThermalObserver() {
        if (thermalObserverStarted) return
        thermalObserverStarted = true
        DownloadThermalGate.startObserving {
            if (!hasLoaded) return@startObserving
            applyStarts(startQueue.promote(DownloadThermalGate.blocksNextFile()))
        }
    }

    private fun adoptLoadedItems() {
        val items = _uiState.value.items
        val running = items
            .filter { it.status == DownloadStatus.Downloading && it.id in activeHandles }
            .sortedBy { it.createdAtEpochMs }
            .map { it.id }
        val pending = items
            .filter { (it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued) && it.id !in activeHandles }
            .sortedBy { it.createdAtEpochMs }
            .map { it.id }
        val started = startQueue.restore(running, pending, DownloadThermalGate.blocksNextFile())
        alignQueuedStatuses()
        applyStarts(started)
    }

    private fun alignQueuedStatuses() {
        val activeIds = startQueue.activeIds()
        val waitingIds = startQueue.waitingIds().toSet()
        val now = DownloadsClock.nowEpochMs()
        val demoted = mutableListOf<DownloadItem>()
        var changed = false
        val updated = _uiState.value.items.map { item ->
            when {
                item.id in waitingIds && item.status != DownloadStatus.Queued -> {
                    changed = true
                    if (item.status == DownloadStatus.Downloading) demoted.add(item)
                    item.copy(status = DownloadStatus.Queued, errorMessage = null, updatedAtEpochMs = now)
                }
                item.id in activeIds && item.status == DownloadStatus.Queued -> {
                    changed = true
                    item.copy(status = DownloadStatus.Downloading, errorMessage = null, updatedAtEpochMs = now)
                }
                else -> item
            }
        }
        if (!changed) return
        publish(updated)
        persistStatus()
        demoted.forEach { item ->
            DownloadsPlatformDownloader.pauseRunningTransfer(item)
            DownloadsPlatformDownloader.updatePartialTarget(
                downloadId = item.id,
                fileName = item.fileName,
                totalBytes = item.totalBytes,
                downloadRunning = false,
            )
        }
    }

    private fun applyStarts(startedIds: List<String>) {
        if (startedIds.isEmpty()) return
        val now = DownloadsClock.nowEpochMs()
        val accepted = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        startedIds.forEach { id ->
            if (id !in activeHandles && activeHandles.size + accepted.size >= MAX_ACTIVE_DOWNLOADS) {
                startQueue.demoteToFront(id)
                deferred.add(id)
            } else {
                accepted.add(id)
            }
        }
        val acceptedSet = accepted.toSet()
        val deferredSet = deferred.toSet()
        var changed = false
        val updated = _uiState.value.items.map { item ->
            when {
                item.id in deferredSet && item.status != DownloadStatus.Queued -> {
                    changed = true
                    item.copy(status = DownloadStatus.Queued, updatedAtEpochMs = now)
                }
                item.id in acceptedSet && item.status != DownloadStatus.Downloading -> {
                    changed = true
                    item.copy(
                        status = DownloadStatus.Downloading,
                        errorMessage = null,
                        updatedAtEpochMs = now,
                    )
                }
                else -> item
            }
        }
        if (changed) {
            publish(updated)
            persistStatus()
        }
        accepted.forEach { id ->
            if (id in activeHandles) return@forEach
            val item = _uiState.value.items.firstOrNull { it.id == id } ?: return@forEach
            if (item.status == DownloadStatus.Downloading) startDownload(item)
        }
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun refreshStoredEarlyPlay(item: DownloadItem): DownloadItem {
        if (
            item.status != DownloadStatus.Downloading &&
            item.status != DownloadStatus.Paused &&
            item.status != DownloadStatus.Queued
        ) {
            return if (item.earlyPlayReady || item.playsWhenDownloadFinishes) {
                item.copy(earlyPlayReady = false, playsWhenDownloadFinishes = false)
            } else {
                item
            }
        }
        val decision = decideEarlyPlay(item.id, item.fileName, item.downloadedBytes)
        return if (
            decision.playable == item.earlyPlayReady &&
            decision.playsWhenDownloadFinishes == item.playsWhenDownloadFinishes
        ) {
            item
        } else {
            item.copy(
                earlyPlayReady = decision.playable,
                playsWhenDownloadFinishes = decision.playsWhenDownloadFinishes,
            )
        }
    }

    private fun decideEarlyPlay(downloadId: String, fileName: String, bytesOnDisk: Long): EarlyPlayDecision {
        val cached = partialPrefixCache[downloadId]
        val prefix = if (cached != null && !shouldRereadPrefix(fileName, cached, bytesOnDisk)) {
            cached
        } else {
            val read = DownloadsPlatformDownloader.readPartialPrefix(fileName, PARTIAL_CLASSIFY_PREFIX_BYTES)
            if (read.isNotEmpty()) partialPrefixCache[downloadId] = read
            read
        }
        return earlyPlayDecision(
            bytesOnDisk = bytesOnDisk,
            fileName = fileName,
            prefix = prefix,
            downloadComplete = false,
        )
    }

    private fun shouldRereadPrefix(fileName: String, prefix: ByteArray, bytesOnDisk: Long): Boolean {
        if (prefix.isEmpty()) return true
        if (prefix.size >= PARTIAL_CLASSIFY_PREFIX_BYTES) return false
        if (bytesOnDisk <= prefix.size.toLong()) return false
        val kind = classifyPartialContainer(fileName, prefix)
        return partialPrefixStart(kind, prefix) == PrefixStart.NotYet
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean = when (status) {
        DownloadStatus.Completed ->
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null || PartialPlaybackLease.isHeld(id)
        DownloadStatus.Downloading,
        DownloadStatus.Paused,
        DownloadStatus.Queued,
        -> earlyPlayReady
        DownloadStatus.Failed -> false
    }
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

internal fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
