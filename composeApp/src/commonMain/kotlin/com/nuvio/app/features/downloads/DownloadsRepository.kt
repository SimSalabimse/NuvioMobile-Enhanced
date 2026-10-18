package com.nuvio.app.features.downloads

import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    private val startQueue = DownloadStartQueue(orderKeyOf = ::orderKeyOf)
    private val persistGate = DownloadPersistGate()
    private var thermalObserverStarted = false
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L
    private val linkRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val linkRefreshAttempts = mutableMapOf<String, Int>()
    private val linkRefreshBytes = mutableMapOf<String, Long>()
    private val linkRefreshHandles = mutableMapOf<String, DownloadsTaskHandle>()

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

    internal fun replaceItems(items: List<DownloadItem>) {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        partialPrefixCache.clear()
        startQueue.clear()
        hasLoaded = true
        publish(items)
        persistStatus()
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

    private fun orderKeyOf(downloadId: String): DownloadOrderKey? {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return null
        val season = item.seasonNumber ?: return null
        val episode = item.episodeNumber ?: return null
        return DownloadOrderKey(showKey = item.parentMetaId.trim(), season = season, episode = episode)
    }

    /**
     * iOS only, from willResignActive: fill the free slots with the next waiting files, lowest
     * episode first, so they are on the background session before the app is in the background.
     * Only [BACKGROUND_SUBMIT_CAP] run at a time; the rest start as earlier ones finish.
     */
    fun submitWaitingForBackground() {
        if (!hasLoaded) return
        startQueue.maxActive = BACKGROUND_SUBMIT_CAP
        applyStarts(startQueue.promote(DownloadThermalGate.blocksNextFile()))
    }

    /** Back in the app: new starts wait for one of the two slots again. Running files keep going. */
    fun restoreForegroundCap() {
        startQueue.maxActive = PlaybackPriority.downloadCap()
        resumeWaitingForAppOpen()
        if (hasLoaded) {
            scheduleSpaceRetry()
            SmartDownloads.retryPending()
        }
    }

    /** Episode time the download of [downloadId] has reached, for the player's buffer bar. */
    fun partialDownloadReachMs(downloadId: String, durationMs: Long): Long? {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return null
        val onDisk = if (item.status == DownloadStatus.Completed) {
            null
        } else {
            DownloadsPlatformDownloader.playableBytesOnDisk(item.fileName)
        }
        return estimatePartialDownloadReachMs(
            downloadedBytes = onDisk?.coerceAtMost(item.downloadedBytes) ?: item.downloadedBytes,
            totalBytes = item.totalBytes,
            durationMs = durationMs,
            complete = item.status == DownloadStatus.Completed,
        )
    }

    /** Contiguous bytes early play can read right now (the whole file once it has finished). */
    fun playableContiguousBytes(downloadId: String): Long? {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return null
        if (item.status == DownloadStatus.Completed) return item.totalBytes ?: item.downloadedBytes
        val onDisk = DownloadsPlatformDownloader.playableBytesOnDisk(item.fileName)
        return onDisk?.coerceAtMost(item.downloadedBytes) ?: item.downloadedBytes
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
            com.nuvio.app.features.analysis.MediaAnalysisScheduler.releaseFile(existing.id)
            DownloadsPlatformDownloader.discardTransfer(existing.id)
            activeHandles.remove(existing.id)?.cancel()
            partialPrefixCache.remove(existing.id)
            startQueue.discard(existing.id)
            deleteFilesOf(existing)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
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
            sourceFilename = stream.behaviorHints.filename?.trim()?.takeIf { it.isNotBlank() },
            sourceBingeGroup = stream.behaviorHints.bingeGroup?.trim()?.takeIf { it.isNotBlank() },
            sourceQualityLabel = streamQualityLabel(stream),
            subtitleRequests = addonSubtitleRequests(contentType, videoId),
            sourceSubtitles = stream.externalSubtitles,
            localFileUri = null,
            fileName = fileName,
            // Queued until the line says it may start: the line orders episodes by number, which
            // needs this row to be in the list first.
            status = DownloadStatus.Queued,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        val toStart = startQueue.request(downloadId, DownloadThermalGate.blocksNextFile())
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
            needsLinkRefresh = item.wantsFreshLink(),
            waitingForAppOpen = false,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persistStatus()
        applyStarts(toStart)
    }

    /** Health check: a cut-off or damaged finished file is deleted and downloaded again. */
    fun redownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Completed) return
        com.nuvio.app.features.analysis.MediaAnalysisScheduler.releaseFile(item.id)
        com.nuvio.app.features.analysis.MediaAnalysisStore.forgetFileData(com.nuvio.app.features.analysis.MediaAnalysisStore.keyFor(item))
        deleteFilesOf(item)
        replaceItem(item.copy(status = DownloadStatus.Failed, downloadedBytes = 0L, localFileUri = null))
        retryDownload(downloadId)
    }

    /** Retry gets a fresh link first when the old one was rejected (see [wantsFreshLink]). */
    fun retryDownload(downloadId: String) {
        linkRefreshAttempts.remove(downloadId)
        resumeDownload(downloadId)
        // Retry re-checks space with a fresh figure; when it still does not fit, say so instead
        // of silently going back to Failed.
        val after = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (after.isWaitingForSpace()) {
            after.errorMessage?.let { com.nuvio.app.core.ui.NuvioToastController.show(it) }
        }
    }

    private var legacySpaceMessage: String? = null

    /** Paused as "Waiting for space", or failed for space by a build before 184. */
    private fun DownloadItem.isWaitingForSpace(): Boolean {
        val message = errorMessage ?: return false
        if (status == DownloadStatus.Paused) return message.startsWith(WAITING_FOR_SPACE_PREFIX)
        if (status != DownloadStatus.Failed) return false
        if (message.endsWith(DOWNLOAD_SPACE_HINT)) return true
        val legacy = legacySpaceMessage
            ?: runCatching { runBlocking { getString(Res.string.downloads_not_enough_space) } }.getOrNull()
                ?.also { legacySpaceMessage = it }
        return message == legacy || message == "Not enough space"
    }

    private var spaceRetryJob: kotlinx.coroutines.Job? = null

    /**
     * Space was freed (delete, orphan clean-up, app back in front, storage view refresh): retry
     * the downloads that stopped for space, oldest first, as many as now fit. A partial file
     * resumes. iOS updates its free-space figure a moment after a delete, so it checks again
     * after a few seconds.
     */
    internal fun scheduleSpaceRetry() {
        spaceRetryJob?.cancel()
        spaceRetryJob = linkRefreshScope.launch {
            retrySpaceFailures()
            kotlinx.coroutines.delay(2_000L)
            retrySpaceFailures()
            kotlinx.coroutines.delay(5_000L)
            retrySpaceFailures()
        }
    }

    internal fun retrySpaceFailures() {
        ensureLoaded()
        val waiting = _uiState.value.items
            .filter { it.isWaitingForSpace() }
            .sortedBy { it.createdAtEpochMs }
        if (waiting.isEmpty()) return
        val volume = DownloadDiskInfo.volumeSpace() ?: return
        // Same check as the start (fresh free space, bytes running downloads still owe), so a
        // resumed one is not parked again at once; each one let through owes its rest too.
        var owed = owedBytes()
        val ids = mutableListOf<String>()
        for (item in waiting) {
            if (spaceAdmission(item.remainingBytes(), item.totalBytes, item.sizeEstimate(), owed, volume) != null) continue
            owed += item.owedBytes()
            ids += item.id
        }
        if (ids.isNotEmpty()) resumeDownloads(ids)
    }

    /**
     * A link-rejected failure, or one from before this build ("Request failed with HTTP 400"),
     * starts again with a fresh link from the same source instead of the stored one.
     */
    private fun DownloadItem.wantsFreshLink(): Boolean =
        needsLinkRefresh ||
            (status == DownloadStatus.Failed && DownloadLinkFailure.legacyStatus(errorMessage) != null)

    /**
     * iOS woke the app for a background task whose link was rejected and this process did not
     * start it (a relaunch). Loads the list if needed and refreshes the link within that wake.
     */
    internal fun onBackgroundLinkRejected(downloadId: String, status: Int) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return
        if (downloadId in linkRefreshHandles) return
        handleLinkRejected(downloadId, status)
    }

    private fun handleLinkRejected(downloadId: String, @Suppress("UNUSED_PARAMETER") status: Int) {
        activeHandles.remove(downloadId)
        val attempts = linkRefreshAttempts[downloadId] ?: 0
        if (attempts >= DownloadLinkFailure.MAX_REFRESHES) {
            failForExpiredLink(downloadId)
            return
        }
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Downloading) return
        refreshLinkAndRestart(item)
    }

    /**
     * Asks the row's addon for the same item again (same addon, then file name, title or quality),
     * swaps in the new link and resumes from the bytes on disk with a Range. A different file size
     * means a different file, so it starts over instead of appending. The row keeps its slot
     * while this runs; pausing or cancelling it drops the result.
     */
    private fun refreshLinkAndRestart(item: DownloadItem) {
        val downloadId = item.id
        if (downloadId in linkRefreshHandles) return
        linkRefreshAttempts[downloadId] = (linkRefreshAttempts[downloadId] ?: 0) + 1
        linkRefreshBytes[downloadId] = item.downloadedBytes
        val placeholder = object : DownloadsTaskHandle {
            override fun cancel() {
                linkRefreshHandles.remove(downloadId)
            }
        }
        linkRefreshHandles[downloadId] = placeholder
        activeHandles[downloadId] = placeholder
        mutateItem(downloadId) { current ->
            current.copy(
                needsLinkRefresh = true,
                waitingForAppOpen = false,
                errorMessage = runBlocking { getString(Res.string.downloads_link_refreshing) },
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }
        val token = DownloadBackgroundTime.begin("NuvioDownloadLinkRefresh")
        linkRefreshScope.launch {
            try {
                val fresh = runCatching {
                    withTimeoutOrNull(LINK_REFRESH_TIMEOUT_MS) { resolveFreshLink(item) }
                }.getOrNull()
                val freshTotal = fresh?.let { link ->
                    runCatching {
                        withTimeoutOrNull(LINK_PROBE_TIMEOUT_MS) {
                            RemoteContentLengthProbe.probe(
                                SeasonProbeTarget(id = downloadId, url = link.url, headers = sanitizeRequestHeaders(link.headers)),
                            )
                        }
                    }.getOrNull()
                }
                finishLinkRefresh(downloadId, placeholder, fresh, freshTotal)
            } finally {
                DownloadBackgroundTime.end(token)
            }
        }
    }

    private fun finishLinkRefresh(
        downloadId: String,
        placeholder: DownloadsTaskHandle,
        fresh: RefreshedLink?,
        freshTotal: Long?,
    ) {
        if (linkRefreshHandles[downloadId] !== placeholder) return
        linkRefreshHandles.remove(downloadId)
        if (activeHandles[downloadId] === placeholder) activeHandles.remove(downloadId)
        val current = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (current.status != DownloadStatus.Downloading) return
        if (fresh == null) {
            if (DownloadBackgroundTime.isAppActive()) failForExpiredLink(downloadId) else waitForAppOpen(downloadId)
            return
        }
        val knownTotal = current.totalBytes?.takeIf { it > 0L }
        val differentFile = freshTotal != null && knownTotal != null && freshTotal != knownTotal
        if (differentFile) {
            partialPrefixCache.remove(downloadId)
            DownloadsPlatformDownloader.removePartialFile(current.fileName)
        }
        val updated = current.copy(
            sourceUrl = fresh.url,
            sourceHeaders = sanitizeRequestHeaders(fresh.headers),
            sourceResponseHeaders = sanitizeResponseHeaders(fresh.responseHeaders),
            needsLinkRefresh = false,
            waitingForAppOpen = false,
            errorMessage = null,
            downloadedBytes = if (differentFile) 0L else current.downloadedBytes,
            totalBytes = freshTotal ?: current.totalBytes,
            earlyPlayReady = if (differentFile) false else current.earlyPlayReady,
            playsWhenDownloadFinishes = if (differentFile) false else current.playsWhenDownloadFinishes,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )
        replaceItem(updated)
        persistStatus()
        if (admit(updated)) startDownload(updated)
    }

    private fun failForExpiredLink(downloadId: String) {
        linkRefreshAttempts.remove(downloadId)
        linkRefreshBytes.remove(downloadId)
        activeHandles.remove(downloadId)
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Failed,
                needsLinkRefresh = true,
                waitingForAppOpen = false,
                errorMessage = runBlocking { getString(Res.string.downloads_error_link_expired) },
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }
        stopPartialTarget(downloadId)
    }

    /** No network for the addon while locked: paused, and refreshed when the app is next opened. */
    private fun waitForAppOpen(downloadId: String) {
        activeHandles.remove(downloadId)
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                needsLinkRefresh = true,
                waitingForAppOpen = true,
                errorMessage = runBlocking { getString(Res.string.downloads_link_refresh_on_open) },
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }
        stopPartialTarget(downloadId)
    }

    private fun stopPartialTarget(downloadId: String) {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        DownloadsPlatformDownloader.updatePartialTarget(
            downloadId = item.id,
            fileName = item.fileName,
            totalBytes = item.totalBytes,
            downloadRunning = false,
        )
    }

    /** Back in the app: downloads whose link refresh had to wait for it carry on. */
    private fun resumeWaitingForAppOpen() {
        if (!hasLoaded || !DownloadBackgroundTime.isAppActive()) return
        val ids = _uiState.value.items
            .filter { it.status == DownloadStatus.Paused && it.waitingForAppOpen }
            .map { it.id }
        if (ids.isNotEmpty()) resumeDownloads(ids)
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

    /**
     * iOS put a file in place for a task this process did not start (a background relaunch).
     * Marks the row Completed if the list is loaded; otherwise the next load does it.
     */
    internal fun markFinishedInBackground(downloadId: String, localFileUri: String, totalBytes: Long?) {
        if (!hasLoaded) return
        activeHandles.remove(downloadId)
        partialPrefixCache.remove(downloadId)
        mutateItem(downloadId) { current ->
            if (current.status == DownloadStatus.Completed) return@mutateItem current
            current.copy(
                status = DownloadStatus.Completed,
                localFileUri = localFileUri,
                downloadedBytes = totalBytes?.takeIf { it > 0L } ?: current.downloadedBytes,
                totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                earlyPlayReady = false,
                playsWhenDownloadFinishes = false,
                errorMessage = null,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }
    }

    fun cancelDownloads(downloadIds: List<String>) {
        downloadIds.forEach(::cancelDownload)
    }

    /**
     * Pause season. Waiting episodes are taken out of the line first; pausing a running one used
     * to free its slot, which started the next waiting episode, which was then paused in turn,
     * cascading through the season and leaving every episode Paused in no useful order.
     */
    fun pauseDownloads(downloadIds: List<String>) {
        ensureLoaded()
        val ids = downloadIds.toSet()
        val now = DownloadsClock.nowEpochMs()
        val waitingIds = _uiState.value.items
            .filter { it.id in ids && it.status == DownloadStatus.Queued }
            .map { it.id }
            .toSet()
        if (waitingIds.isNotEmpty()) {
            waitingIds.forEach(startQueue::discard)
            publish(
                _uiState.value.items.map { item ->
                    if (item.id in waitingIds) item.copy(status = DownloadStatus.Paused, updatedAtEpochMs = now) else item
                },
            )
            persistStatus()
        }
        downloadIds.forEach(::pauseDownload)
    }

    /**
     * Resume season. Episodes that already have bytes on disk (were running, or paused part way)
     * go first, lowest episode first; then the ones that never started, lowest first. Two run.
     * It used to resume them one by one walking the list backwards; every resume with a free slot
     * started immediately, so the last (newest) episodes took the two slots.
     */
    fun resumeDownloads(downloadIds: List<String>) {
        ensureLoaded()
        val ids = downloadIds.toSet()
        val resumable = _uiState.value.items.filter {
            it.id in ids && (it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed)
        }
        if (resumable.isEmpty()) return
        val ordered = resumable.sortedWith(
            compareBy<DownloadItem> { if (it.hasBytesOnDisk()) 0 else 1 }
                .thenBy { it.seasonNumber ?: Int.MAX_VALUE }
                .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
                .thenBy { it.createdAtEpochMs },
        )
        val orderedIds = ordered.map { it.id }
        val now = DownloadsClock.nowEpochMs()
        val idSet = orderedIds.toSet()
        publish(
            _uiState.value.items.map { item ->
                if (item.id in idSet) {
                    item.copy(
                        status = DownloadStatus.Queued,
                        errorMessage = null,
                        localFileUri = null,
                        needsLinkRefresh = item.wantsFreshLink(),
                        waitingForAppOpen = false,
                        updatedAtEpochMs = now,
                    )
                } else {
                    item
                }
            },
        )
        val toStart = startQueue.requestAllAtFront(orderedIds, DownloadThermalGate.blocksNextFile())
        persistStatus()
        applyStarts(toStart)
    }

    private fun DownloadItem.hasBytesOnDisk(): Boolean =
        downloadedBytes > 0L || (DownloadsPlatformDownloader.playableBytesOnDisk(fileName) ?: 0L) > 0L

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        // Let go of the file first (analysis run, preview reader, audio capture), then stop the
        // transfer without resume data, then delete and check.
        com.nuvio.app.features.analysis.MediaAnalysisScheduler.releaseFile(downloadId)
        DownloadsPlatformDownloader.discardTransfer(downloadId)
        activeHandles.remove(downloadId)?.cancel()
        linkRefreshAttempts.remove(downloadId)
        linkRefreshBytes.remove(downloadId)
        partialPrefixCache.remove(downloadId)
        deleteFilesOf(item)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persistStatus()
        forgetAnalysedFile(item)
        applyStarts(startQueue.release(downloadId, DownloadThermalGate.blocksNextFile()))
        appUsageDirty = true
        scheduleSpaceRetry()
        SmartDownloads.retryPending()
    }

    /**
     * The analysed file is gone and no download of any profile (the saved lists, this one already
     * without [item]) still uses it: drop its preview thumbnails and other file-only data. The
     * skip times, the user's marks and the fingerprints stay with the episode.
     */
    private fun forgetAnalysedFile(item: DownloadItem) {
        val key = com.nuvio.app.features.analysis.MediaAnalysisStore.keyFor(item)
        if (com.nuvio.app.features.analysis.MediaAnalysisStore.load(key)?.fileName != item.fileName) return
        if (item.fileName in storedFileNames(DownloadDiskInfo.allProfilePayloads())) return
        com.nuvio.app.features.analysis.MediaAnalysisStore.forgetFileData(key)
    }

    /**
     * Deletes the finished file (checked in every place it can be) and the partial data. A file
     * that is still there, or a finished file that was found nowhere, goes on the failed-deletes
     * list, retried at launch and on Clean up leftovers.
     */
    private fun deleteFilesOf(item: DownloadItem) {
        val playable = runCatching { playableLocalFileUri(item) }.getOrNull()
        val outcome = DownloadsPlatformDownloader.removeFileVerified(
            listOf(playable, item.localFileUri),
            item.fileName,
        )
        DownloadsPlatformDownloader.removePartialFile(item.fileName)
        val expectedFile = item.status == DownloadStatus.Completed
        if (outcome == FileDeleteOutcome.StillThere || (outcome == FileDeleteOutcome.NotFound && expectedFile)) {
            PendingFileDeletes.add(item.fileName, item.localFileUri)
        }
    }

    /** Failed deletes from before: try again (at launch and on cleanup). Returns how many went. */
    internal fun retryFailedDeletes(): Int {
        val owned = _uiState.value.items.map { it.fileName }.toSet() +
            storedFileNames(DownloadDiskInfo.allProfilePayloads())
        var done = 0
        PendingFileDeletes.retry { entry ->
            // Downloaded again since: that file is in use.
            if (entry.fileName in owned) return@retry true
            val outcome = DownloadsPlatformDownloader.removeFileVerified(listOf(entry.localFileUri), entry.fileName)
            if (outcome == FileDeleteOutcome.Deleted) done++
            outcome == FileDeleteOutcome.Deleted
        }
        if (done > 0) {
            appUsageDirty = true
            scheduleSpaceRetry()
        }
        return done
    }

    /** "Failed / incomplete downloads": rows that stopped part way, with their partial bytes. */
    internal fun deleteIncompleteDownloads(ids: List<String>) {
        ensureLoaded()
        val incomplete = _uiState.value.items
            .filter { it.id in ids && (it.status == DownloadStatus.Failed || it.status == DownloadStatus.Paused) }
            .map { it.id }
        incomplete.forEach(::cancelDownload)
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
        resumeWaitingForAppOpen()
        runCatching { retryFailedDeletes() }
        StorageAutopilot.run()
    }

    private fun startDownload(item: DownloadItem) {
        if (item.needsLinkRefresh) {
            refreshLinkAndRestart(item)
            return
        }
        val request = DownloadPlatformRequest(item)
        // A callback from an earlier, cancelled attempt (e.g. stopped for space) must not fail
        // or pause the attempt Retry just started.
        val attempt = (startAttempts[item.id] ?: 0) + 1
        startAttempts[item.id] = attempt
        fun staleAttempt() = startAttempts[item.id] != attempt

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = onProgress@{ downloadedBytes, totalBytes ->
                val current = _uiState.value.items.firstOrNull { it.id == item.id } ?: return@onProgress
                if (current.status != DownloadStatus.Downloading) return@onProgress
                val shortfall = outOfSpaceWhileDownloading(current)
                if (shortfall != null) {
                    waitForSpace(current.id, shortfall)
                    return@onProgress
                }
                val safeBytes = downloadedBytes.coerceAtLeast(0L)
                // A refreshed link that is really moving again gets its refresh attempts back.
                linkRefreshBytes[item.id]?.let { atRefresh ->
                    if (safeBytes - atRefresh >= DownloadRetryPolicy.PROGRESS_RESETS_ATTEMPTS_BYTES) {
                        linkRefreshBytes.remove(item.id)
                        linkRefreshAttempts.remove(item.id)
                    }
                }
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
                linkRefreshAttempts.remove(item.id)
                linkRefreshBytes.remove(item.id)
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
                com.nuvio.app.features.analysis.MediaAnalysisScheduler.kick()
                com.nuvio.app.features.offline.OfflineArtwork.cache(item)
                StorageAutopilot.run()
            },
            onFailure = onFailure@{ message ->
                if (staleAttempt()) return@onFailure
                DownloadLinkFailure.decode(message)?.let { status ->
                    handleLinkRejected(item.id, status)
                    return@onFailure
                }
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
            onPaused = onPaused@{
                if (staleAttempt()) return@onPaused
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
            // A finished file owes nothing any more: a waiting one may fit now.
            if (after?.status == DownloadStatus.Completed) retrySpaceFailures()
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
        runCatching { DownloadSpeeds.onItems(items, DownloadsClock.nowEpochMs()) }
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

    /**
     * How many downloads may run while the player is open (see [PlaybackPriority]): fewer while
     * playing, none while it buffers, the normal cap again when it closes. Extra transfers go back
     * to the front of the line (paused, resumed in place later).
     */
    fun setPlaybackDownloadCap(cap: Int) {
        if (startQueue.maxActive == cap) return
        val shrinking = cap < startQueue.maxActive
        startQueue.maxActive = cap
        if (!hasLoaded) return
        if (shrinking) {
            if (startQueue.shrinkTo(cap).isNotEmpty()) alignQueuedStatuses()
        } else {
            applyStarts(startQueue.promote(DownloadThermalGate.blocksNextFile()))
        }
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
            if (id !in activeHandles && activeHandles.size + accepted.size >= startQueue.maxActive) {
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
            if (item.status != DownloadStatus.Downloading) return@forEach
            if (admit(item)) startDownload(item)
        }
    }

    /**
     * The one space gate before any bytes are fetched, for every start: new, resumed, retried,
     * auto-retried, after a link refresh and when iOS restarts a tracked task. A file that does
     * not fit (its rest or, size unknown, the show's typical episode size, plus the reserve, against
     * free space minus what running downloads still owe) is paused as "Waiting for space" and
     * starts by itself once it fits ([retrySpaceFailures]).
     */
    private fun admit(item: DownloadItem): Boolean {
        val shortfall = spaceAdmission(
            remainingBytes = item.remainingBytes(),
            fileBytes = item.totalBytes,
            estimateBytes = item.sizeEstimate(),
            owedBytes = owedBytes(excluding = item.id),
            volume = DownloadDiskInfo.volumeSpace(),
        ) ?: return true
        waitForSpace(item.id, shortfall)
        return false
    }

    /** iOS is about to restart a tracked task (no live system task left): same gate. */
    internal fun admitRestart(downloadId: String): Boolean {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
        return admit(item)
    }

    /** Bytes the running downloads still have to write (unknown size: the estimate less what is on disk). */
    internal fun owedBytes(excluding: String? = null): Long = _uiState.value.items
        .filter { it.id != excluding && it.status == DownloadStatus.Downloading && it.id in activeHandles }
        .sumOf { it.owedBytes() }

    private fun DownloadItem.owedBytes(): Long =
        remainingBytes() ?: (sizeEstimate() - downloadedBytes).coerceAtLeast(0L)

    /** Size assumed when the source gives none: the show's typical episode, else 2 GB. */
    private fun DownloadItem.sizeEstimate(): Long =
        typicalEpisodeBytes(parentMetaId, _uiState.value.items) ?: GUESSED_EPISODE_BYTES

    internal fun DownloadItem.remainingBytes(): Long? =
        totalBytes?.takeIf { it > 0L }?.let { (it - downloadedBytes).coerceAtLeast(0L) }

    private val lastSpaceCheckAt = mutableMapOf<String, Long>()
    private val startAttempts = mutableMapOf<String, Int>()

    /** Checked every so often while a file downloads, so it stops before the disk is full. */
    private fun outOfSpaceWhileDownloading(item: DownloadItem): DownloadSpaceShortfall? {
        val now = DownloadsClock.nowEpochMs()
        val last = lastSpaceCheckAt[item.id] ?: 0L
        if (now - last < SPACE_CHECK_INTERVAL_MS) return null
        lastSpaceCheckAt[item.id] = now
        return spaceRunningOut(DownloadDiskInfo.volumeSpace())
    }

    /**
     * Not enough free space: the download does not start, or stops with what it has, instead of
     * filling the disk. It is paused as "Waiting for space (needs ~X, Y free)" and its slot goes to
     * the next one, which meets the same gate. It continues from the bytes on disk once it fits.
     */
    private fun waitForSpace(downloadId: String, shortfall: DownloadSpaceShortfall) {
        activeHandles.remove(downloadId)?.cancel()
        lastSpaceCheckAt.remove(downloadId)
        val message = waitingForSpaceMessage(shortfall)
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                errorMessage = message,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
        }
        val paused = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        DownloadsPlatformDownloader.updatePartialTarget(
            downloadId = downloadId,
            fileName = paused.fileName,
            totalBytes = paused.totalBytes,
            downloadRunning = false,
        )
    }

    /** Storage view: what is on disk, per title and season, and leftovers no download owns. */
    internal fun storageSummary(): DownloadStorageSummary {
        ensureLoaded()
        val items = _uiState.value.items
        val mine = items.map { it.fileName }.toSet()
        val others = storedFileNames(DownloadDiskInfo.allProfilePayloads()) - mine
        val summary = summarizeDownloadStorage(
            items = items,
            files = DownloadDiskInfo.listDownloadFiles(),
            otherProfileFileNames = others,
            volume = DownloadDiskInfo.volumeSpace(),
        )
        return summary.copy(app = appUsage(mine + others))
    }

    private var cachedAppUsage: AppStorageUsage? = null
    private var cachedAppUsageAt = 0L
    private var appUsageDirty = true

    /** The whole-app walk is heavier: at most once a minute unless something was deleted. */
    private fun appUsage(owned: Set<String>): AppStorageUsage? {
        val now = DownloadsClock.nowEpochMs()
        if (!appUsageDirty && cachedAppUsage != null && now - cachedAppUsageAt < 60_000L) return cachedAppUsage
        val usage = runCatching { DownloadDiskInfo.appStorageUsage(owned) }.getOrNull() ?: cachedAppUsage
        cachedAppUsage = usage
        cachedAppUsageAt = now
        appUsageDirty = false
        return usage
    }

    /**
     * "Clean up leftovers": files in the downloads folder no download of any profile owns, loose
     * subtitle files and stale resume data. Each one is checked against the current rows first.
     * Returns the bytes freed.
     */
    internal fun cleanUpLeftovers(): Long {
        ensureLoaded()
        runCatching { retryFailedDeletes() }
        val summary = storageSummary()
        val owned = _uiState.value.items.map { it.fileName } + storedFileNames(DownloadDiskInfo.allProfilePayloads())
        var freed = 0L
        summary.orphans
            .filter { file -> owned.none { ownsDiskFile(it, file.name) } }
            .forEach { file -> if (DownloadDiskInfo.deleteDownloadFile(file.name)) freed += file.sizeBytes }
        summary.app?.leftovers.orEmpty().forEach { leftover ->
            val name = leftover.path.substringAfterLast('/').removeSuffix(".resumedata")
            if (owned.none { it.isNotBlank() && (it == name || ownsDiskFile(it, name)) }) {
                if (DownloadDiskInfo.deleteLeftover(leftover.path)) freed += leftover.sizeBytes
            }
        }
        appUsageDirty = true
        scheduleSpaceRetry()
        SmartDownloads.retryPending()
        return freed
    }

    /** Deletes leftover files; re-checks each against the current rows first. */
    internal fun clearOrphans(orphans: List<DiskFile>) {
        val owned = _uiState.value.items.map { it.fileName } +
            storedFileNames(DownloadDiskInfo.allProfilePayloads())
        orphans
            .filter { file -> owned.none { ownsDiskFile(it, file.name) } }
            .forEach { DownloadDiskInfo.deleteDownloadFile(it.name) }
        appUsageDirty = true
        scheduleSpaceRetry()
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

private const val SPACE_CHECK_INTERVAL_MS = 10_000L
private const val LINK_REFRESH_TIMEOUT_MS = 25_000L
private const val LINK_PROBE_TIMEOUT_MS = 8_000L

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
