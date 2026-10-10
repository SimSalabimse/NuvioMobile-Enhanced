package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSData
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForHTTPHeaderField
import platform.UIKit.UIApplication
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val BACKGROUND_SESSION_IDENTIFIER = "com.nuvio.app.downloads"
internal const val DOWNLOAD_RESOURCE_TIMEOUT_SECONDS = 24.0 * 60.0 * 60.0
private const val PROGRESS_MIN_INTERVAL_SECONDS = 0.5
private const val PROGRESS_MIN_BYTE_DELTA = 512L * 1024L

private val backgroundSessionCompletionHandlers = mutableMapOf<String, () -> Unit>()

/**
 * Addon subtitles are fetched alongside the video. The download itself runs on a background
 * NSURLSession with no coroutine of its own, so the subtitle fetch gets a scope here and is
 * tracked per download so cancelling the download cancels it too.
 */
private val subtitlePreparationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val subtitlePreparationJobs = mutableMapOf<String, Job>()

@OptIn(ExperimentalForeignApi::class)
private fun prepareDownloadSubtitles(downloadId: String, request: DownloadPlatformRequest) {
    subtitlePreparationJobs.remove(downloadId)?.cancel()
    val destinationUri = resolveDownloadsBaseDirectory().withAccess { downloadsDirectory ->
        NSURL.fileURLWithPath("$downloadsDirectory/${request.destinationFileName}").absoluteString
    } ?: return
    val job = subtitlePreparationScope.launch {
        try {
            DownloadSubtitles.prepare(request.item, destinationUri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Bundled subtitles are best effort; never fail the video download over them.
        }
    }
    subtitlePreparationJobs[downloadId] = job
    job.invokeOnCompletion { subtitlePreparationJobs.remove(downloadId) }
}

private fun cancelDownloadSubtitles(downloadId: String) {
    subtitlePreparationJobs.remove(downloadId)?.cancel()
}

fun handleDownloadsBackgroundEvents(
    identifier: String,
    completionHandler: () -> Unit,
) {
    backgroundSessionCompletionHandlers[identifier] = completionHandler
    IosBackgroundDownloadCoordinator.ensureSessionCreated()
}

fun pauseDownloadsForAppBackground() {
    IosForegroundDownloads.onAppEnteredBackground()
}

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsPlatformDownloader {
    actual fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle {
        prepareDownloadSubtitles(request.item.id, request)
        IosForegroundDownloads.ensureObserver()
        IosTransferControl.allow(request.item.id)
        val callbacks = DownloadCallbacks(onProgress, onSuccess, onFailure)
        if (IosForegroundDownloads.shouldUseForeground()) {
            IosBackgroundDownloadCoordinator.stopKeepingPartial(request.item.id) {
                if (IosTransferControl.isSuppressed(request.item.id)) return@stopKeepingPartial
                if (!IosForegroundDownloads.start(request, callbacks)) {
                    callbacks.onFailure(
                        runBlocking { getString(Res.string.downloads_error_finalize_file_failed) },
                    )
                }
            }
        } else {
            val handedOff = IosForegroundDownloads.handoffToBackground(request.item.id) { size ->
                if (IosTransferControl.isSuppressed(request.item.id)) return@handoffToBackground
                IosBackgroundDownloadCoordinator.startOrResume(
                    downloadId = request.item.id,
                    request = request,
                    rangeStart = size,
                    callbacks = callbacks,
                )
            }
            if (!handedOff) {
                IosBackgroundDownloadCoordinator.startOrResume(
                    downloadId = request.item.id,
                    request = request,
                    rangeStart = null,
                    callbacks = callbacks,
                )
            }
        }
        return IosDownloadsTaskHandle(request.item.id)
    }

    actual fun pauseRunningTransfer(item: DownloadItem) {
        IosForegroundDownloads.cancel(item.id)
        IosBackgroundDownloadCoordinator.cancelDownload(item.id)
    }

    actual fun restoreItem(item: DownloadItem): DownloadItem =
        if (item.status == DownloadStatus.Downloading) {
            item.copy(status = DownloadStatus.Paused, errorMessage = null)
        } else {
            item
        }

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        return resolveDownloadsBaseDirectory().withAccess { downloadsDirectory ->
            val path = localFileUri.toLocalPath() ?: return@withAccess false
            if (NSFileManager.defaultManager.fileExistsAtPath(path)) {
                return@withAccess removePathIfExists(path)
            }

            val fileName = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return@withAccess false
            removePathIfExists("$downloadsDirectory/$fileName")
        }
    }

    actual fun removePartialFile(destinationFileName: String): Boolean =
        resolveDownloadsBaseDirectory().withAccess { downloadsDirectory ->
            val destinationPath = "$downloadsDirectory/$destinationFileName"
            NSURL.fileURLWithPath(destinationPath).absoluteString?.let { uri ->
                DownloadSubtitleStorage(uri).remove()
            }
            IosBackgroundDownloadCoordinator.discardResumeData(destinationFileName)
            IosDeferredPartialRename.clearFileName(destinationFileName)
            removePathIfExists("$destinationPath.part")
        }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? =
        resolveDownloadsBaseDirectory().withAccess { downloadsDirectory ->
            localFileUri?.toLocalPath()
                ?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
                ?.let { path ->
                    return@withAccess NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
                }

            val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
                ?: localFileUri?.toLocalPath()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: return@withAccess null
            val currentPath = "$downloadsDirectory/$fileName"
            if (NSFileManager.defaultManager.fileExistsAtPath(currentPath)) {
                NSURL.fileURLWithPath(currentPath).absoluteString ?: "file://$currentPath"
            } else {
                null
            }
        }

    actual fun openDownloadsDirectory(): Boolean =
        resolveDownloadsBaseDirectory().withAccess { downloadsDirectory ->
            val fileUrl = NSURL.fileURLWithPath(downloadsDirectory).absoluteString
                ?.takeIf { it.startsWith("file://") }
                ?: return@withAccess false
            val url = NSURL(string = "shareddocuments://" + fileUrl.removePrefix("file://"))
            val application = UIApplication.sharedApplication
            if (!application.canOpenURL(url)) return@withAccess false

            application.openURL(
                url = url,
                options = emptyMap<Any?, Any>(),
                completionHandler = null,
            )
            true
        }

    actual fun readPartialPrefix(destinationFileName: String, maxBytes: Int): ByteArray {
        if (maxBytes <= 0 || destinationFileName.isBlank()) return ByteArray(0)
        return resolveDownloadsBaseDirectory().withAccess { directory ->
            val partial = "$directory/$destinationFileName.part"
            val finished = "$directory/$destinationFileName"
            val path = when {
                NSFileManager.defaultManager.fileExistsAtPath(partial) -> partial
                NSFileManager.defaultManager.fileExistsAtPath(finished) -> finished
                else -> return@withAccess ByteArray(0)
            }
            readFilePrefix(path, maxBytes)
        }
    }

    actual fun updatePartialTarget(
        downloadId: String,
        fileName: String,
        totalBytes: Long?,
        downloadRunning: Boolean,
    ) {
        IosPartialPlaybackServer.update(
            downloadId = downloadId,
            fileName = fileName,
            totalBytes = totalBytes,
            running = downloadRunning,
        )
    }

    actual fun partialPlaybackUrl(downloadId: String): String? = IosPartialPlaybackServer.url(downloadId)

    actual fun finishPartialPlayback(downloadId: String): String? = finishIosPartialPlayback(downloadId)
}

@OptIn(ExperimentalForeignApi::class)
private fun readFilePrefix(path: String, maxBytes: Int): ByteArray {
    if (maxBytes <= 0) return ByteArray(0)
    val file = fopen(path, "rb") ?: return ByteArray(0)
    return try {
        val buffer = ByteArray(maxBytes)
        val read = buffer.usePinned { pinned ->
            fread(pinned.addressOf(0), 1uL, maxBytes.convert(), file).toInt()
        }
        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
    } finally {
        fclose(file)
    }
}

private class IosDownloadsTaskHandle(
    private val downloadId: String,
) : DownloadsTaskHandle {
    override fun cancel() {
        cancelDownloadSubtitles(downloadId)
        IosTransferControl.suppress(downloadId)
        IosForegroundDownloads.cancel(downloadId)
        IosBackgroundDownloadCoordinator.cancelDownload(downloadId)
    }
}

internal class DownloadCallbacks(
    val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    val onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
    val onFailure: (message: String) -> Unit,
)

/** Bookkeeping for one in-flight NSURLSessionDownloadTask, alive only as long as this process is. */
private class ActiveDownload(
    val request: DownloadPlatformRequest,
    val taskIdentifier: ULong,
    var callbacks: DownloadCallbacks?,
    val resumedFromData: Boolean = false,
    var retriedWithoutRange: Boolean = false,
    var deliberatelyCancelled: Boolean = false,
    var afterStop: (() -> Unit)? = null,
    var lastProgressBytes: Long = -1L,
    var lastProgressTimestampSeconds: Double = 0.0,
)

@OptIn(ExperimentalForeignApi::class)
internal val IosBackgroundDownloadCoordinator: IosBackgroundDownloadCoordinatorImpl by lazy {
    IosBackgroundDownloadCoordinatorImpl()
}

@OptIn(ExperimentalForeignApi::class)
internal class IosBackgroundDownloadCoordinatorImpl : NSObject(), NSURLSessionDownloadDelegateProtocol {
    private var session: NSURLSession? = null
    private val activeDownloads = mutableMapOf<String, ActiveDownload>()
    private val tasksByDownloadId = mutableMapOf<String, NSURLSessionDownloadTask>()

    private val pendingPauses = mutableMapOf<String, PendingPause>()

    private class PendingPause(val fileName: String) {
        var discard: Boolean = false
        var deferredStart: (() -> Unit)? = null
    }

    fun ensureSessionCreated(): NSURLSession {
        session?.let { return it }
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(
            BACKGROUND_SESSION_IDENTIFIER,
        ).apply {
            val policy = speedFirstBackgroundSessionPolicy
            timeoutIntervalForResource = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS
            discretionary = policy.discretionary
            allowsCellularAccess = policy.allowsCellularAccess
            sessionSendsLaunchEvents = policy.sendsLaunchEvents
        }
        val created = NSURLSession.sessionWithConfiguration(
            configuration = configuration,
            delegate = this,
            delegateQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 },
        )
        session = created
        return created
    }

    fun activeDownloadIds(): List<String> = activeDownloads.keys.toList()

    fun peek(downloadId: String): Pair<DownloadPlatformRequest, DownloadCallbacks?>? {
        val active = activeDownloads[downloadId] ?: return null
        return active.request to active.callbacks
    }

    /**
     * Cancels the background task without appending its temp file.
     * [afterStop] runs once the task is gone and this writer has released the file.
     */
    fun stopKeepingPartial(downloadId: String, afterStop: () -> Unit) {
        val active = activeDownloads[downloadId]
        if (active == null) {
            afterStop()
            return
        }
        active.deliberatelyCancelled = true
        active.afterStop = afterStop
        discardResumeData(active.request.destinationFileName)
        val task = tasksByDownloadId[downloadId]
        if (task == null) {
            activeDownloads.remove(downloadId)
            IosWriterOwnership.release(downloadId, DownloadWriterKind.Background)
            active.afterStop = null
            afterStop()
            return
        }
        task.cancel()
    }

    fun startOrResume(
        downloadId: String,
        request: DownloadPlatformRequest,
        rangeStart: Long?,
        callbacks: DownloadCallbacks?,
    ): Boolean {
        val existing = activeDownloads[downloadId]
        if (existing != null) {
            existing.callbacks = callbacks ?: existing.callbacks
            return true
        }

        pendingPauses[downloadId]?.let { pending ->
            pending.deferredStart = { startOrResume(downloadId, request, rangeStart, callbacks) }
            return true
        }
        if (IosTransferControl.isSuppressed(downloadId)) return false
        if (!IosWriterOwnership.acquire(downloadId, DownloadWriterKind.Background)) return false

        var startedTask = false
        try {
            startedTask = startTask(downloadId, request, rangeStart, callbacks)
        } finally {
            if (!startedTask) {
                activeDownloads.remove(downloadId)
                tasksByDownloadId.remove(downloadId)
                IosWriterOwnership.release(downloadId, DownloadWriterKind.Background)
            }
        }
        return startedTask
    }

    private fun startTask(
        downloadId: String,
        request: DownloadPlatformRequest,
        rangeStart: Long?,
        callbacks: DownloadCallbacks?,
    ): Boolean {
        if (rangeStart == null) {
            val resumeData = takeResumeData(request.destinationFileName)
            if (resumeData != null) {
                val task = ensureSessionCreated().downloadTaskWithResumeData(resumeData)
                task.taskDescription = downloadId
                activeDownloads[downloadId] = ActiveDownload(
                    request = request,
                    taskIdentifier = task.taskIdentifier,
                    callbacks = callbacks,
                    resumedFromData = true,
                )
                tasksByDownloadId[downloadId] = task
                if (IosTransferControl.isSuppressed(downloadId)) {
                    activeDownloads[downloadId]?.deliberatelyCancelled = true
                    task.cancel()
                    return false
                }
                task.resume()
                return true
            }
        } else {
            removePathIfExists(resumeDataPath(request.destinationFileName))
        }

        val base = resolveDownloadsBaseDirectory()
        val started = base.scopedUrl?.startAccessingSecurityScopedResource() ?: false
        try {
            val tempPath = "${base.path}/${request.destinationFileName}.part"
            val resumeFromBytes = rangeStart ?: fileSizeOrNull(tempPath)?.coerceAtLeast(0L) ?: 0L

            val nativeRequest = buildRequest(request, rangeStart = resumeFromBytes.takeIf { it > 0L })
            val task = ensureSessionCreated().downloadTaskWithRequest(nativeRequest)
            task.taskDescription = downloadId

            activeDownloads[downloadId] = ActiveDownload(
                request = request,
                taskIdentifier = task.taskIdentifier,
                callbacks = callbacks,
            )
            tasksByDownloadId[downloadId] = task
            callbacks?.onProgress?.invoke(resumeFromBytes, null)
            if (IosTransferControl.isSuppressed(downloadId)) {
                activeDownloads[downloadId]?.deliberatelyCancelled = true
                task.cancel()
                return false
            }
            task.resume()
            return true
        } finally {
            if (started) base.scopedUrl?.stopAccessingSecurityScopedResource()
        }
    }

    fun cancelDownload(downloadId: String) {
        val active = activeDownloads.remove(downloadId)
        active?.deliberatelyCancelled = true
        if (active != null) {
            IosWriterOwnership.release(downloadId, DownloadWriterKind.Background)
        }
        val task = tasksByDownloadId.remove(downloadId) ?: return
        val fileName = active?.request?.destinationFileName
        if (fileName == null) {
            task.cancel()
            return
        }

        val pending = PendingPause(fileName)
        pendingPauses[downloadId] = pending
        task.cancelByProducingResumeData { resumeData ->
            dispatch_async(dispatch_get_main_queue()) {
                if (pendingPauses[downloadId] === pending) pendingPauses.remove(downloadId)
                if (resumeData != null && !pending.discard) {
                    writeResumeData(fileName, resumeData)
                }
                pending.deferredStart?.invoke()
            }
        }
    }

    fun discardResumeData(fileName: String) {
        pendingPauses.values
            .filter { it.fileName == fileName }
            .forEach { it.discard = true }
        removePathIfExists(resumeDataPath(fileName))
    }

    private fun activeFor(task: NSURLSessionTask): Pair<String, ActiveDownload>? {
        val downloadId = task.taskDescription ?: return null
        val active = activeDownloads[downloadId] ?: return null
        if (active.taskIdentifier != task.taskIdentifier) return null
        return downloadId to active
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didResumeAtOffset: Long,
        expectedTotalBytes: Long,
    ) {
        val (downloadId, _) = activeFor(downloadTask) ?: return
        reportProgress(
            downloadId = downloadId,
            downloadedBytes = didResumeAtOffset,
            totalBytes = expectedTotalBytes.takeIf { it > 0L },
        )
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        val (downloadId, _) = activeFor(downloadTask) ?: return
        reportProgress(
            downloadId = downloadId,
            downloadedBytes = resumeOffset(downloadTask) + totalBytesWritten,
            totalBytes = totalBytesExpectedToWrite.takeIf { it > 0L }
                ?.let { resumeOffset(downloadTask) + it },
        )
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        val downloadId = downloadTask.taskDescription ?: return
        val active = activeFor(downloadTask)?.second
        if (active == null || active.deliberatelyCancelled) {
            runCatching { NSFileManager.defaultManager.removeItemAtPath(didFinishDownloadingToURL.path.orEmpty(), null) }
            return
        }
        val request = active.request

        val statusCode = (downloadTask.response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 200
        val base = resolveDownloadsBaseDirectory()
        val scopedStarted = base.scopedUrl?.startAccessingSecurityScopedResource() ?: false
        var readyToFinalize = false
        var finalizeCallbacks: DownloadCallbacks? = null
        try {
            val tempPath = "${base.path}/${request.destinationFileName}.part"
            val requestedRange = resumeOffset(downloadTask)

            if (statusCode == 416 && active.retriedWithoutRange != true) {
                val callbacks = active.callbacks
                activeDownloads.remove(downloadId)
                tasksByDownloadId.remove(downloadId)
                val restarted = startOrResume(
                    downloadId = downloadId,
                    request = request,
                    rangeStart = 0L,
                    callbacks = callbacks,
                )
                if (restarted) {
                    activeDownloads[downloadId]?.retriedWithoutRange = true
                } else {
                    IosWriterOwnership.release(downloadId, DownloadWriterKind.Background)
                    callbacks?.onFailure(
                        runBlocking { getString(Res.string.network_request_failed_http, statusCode) },
                    )
                }
                return
            }

            if (statusCode !in 200..299) {
                finishWithFailure(
                    downloadId,
                    runBlocking { getString(Res.string.network_request_failed_http, statusCode) },
                )
                return
            }

            val isPartialResume = statusCode == 206 && requestedRange > 0L
            if (!isPartialResume) {
                removePathIfExists(tempPath)
            }
            val appended = appendFile(
                fromPath = didFinishDownloadingToURL.path.orEmpty(),
                toPath = tempPath,
                append = isPartialResume,
            )
            if (!appended) {
                finishWithFailure(
                    downloadId,
                    runBlocking { getString(Res.string.downloads_error_finalize_file_failed) },
                )
                return
            }

            finalizeCallbacks = activeDownloads.remove(downloadId)?.callbacks
            tasksByDownloadId.remove(downloadId)
            readyToFinalize = true
        } finally {
            if (scopedStarted) base.scopedUrl?.stopAccessingSecurityScopedResource()
        }
        if (readyToFinalize) {
            finalizeIosPartialFile(downloadId, request.destinationFileName, finalizeCallbacks)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        val (downloadId, active) = activeFor(task) ?: return
        if (tasksByDownloadId[downloadId]?.taskIdentifier == task.taskIdentifier) {
            tasksByDownloadId.remove(downloadId)
        }
        if (active.deliberatelyCancelled) {
            val afterStop = active.afterStop
            active.afterStop = null
            if (activeDownloads[downloadId] === active) {
                activeDownloads.remove(downloadId)
                IosWriterOwnership.release(downloadId, DownloadWriterKind.Background)
            }
            afterStop?.invoke()
            return
        }
        if (didCompleteWithError == null) return
        val isCancelled = didCompleteWithError.domain == NSURLErrorDomain &&
            didCompleteWithError.code == NSURLErrorCancelled
        if (active.resumedFromData && !isCancelled) {
            activeDownloads.remove(downloadId)
            val callbacks = active.callbacks
            val restarted = startOrResume(
                downloadId,
                active.request,
                rangeStart = 0L,
                callbacks = callbacks,
            )
            if (!restarted) {
                IosWriterOwnership.release(downloadId, DownloadWriterKind.Background)
                callbacks?.onFailure(didCompleteWithError.localizedDescription)
            }
            return
        }
        (didCompleteWithError.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData)?.let { data ->
            writeResumeData(active.request.destinationFileName, data)
        }
        finishWithFailure(downloadId, didCompleteWithError.localizedDescription)
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = session.configuration.identifier ?: return
        backgroundSessionCompletionHandlers.remove(identifier)?.invoke()
    }

    private fun finishWithFailure(downloadId: String, message: String) {
        val callbacks = activeDownloads.remove(downloadId)?.callbacks
        tasksByDownloadId.remove(downloadId)
        IosWriterOwnership.release(downloadId, DownloadWriterKind.Background)
        callbacks?.onFailure(message)
    }

    private fun reportProgress(downloadId: String, downloadedBytes: Long, totalBytes: Long?) {
        val active = activeDownloads[downloadId]
        val now = NSDate().timeIntervalSince1970
        val byteDelta = downloadedBytes - (active?.lastProgressBytes ?: -1L)
        val timeDelta = now - (active?.lastProgressTimestampSeconds ?: 0.0)
        val reachedEnd = totalBytes != null && downloadedBytes >= totalBytes
        if (
            active != null &&
            active.lastProgressBytes >= 0L &&
            !reachedEnd &&
            byteDelta < PROGRESS_MIN_BYTE_DELTA &&
            timeDelta < PROGRESS_MIN_INTERVAL_SECONDS
        ) {
            return
        }
        active?.lastProgressBytes = downloadedBytes
        active?.lastProgressTimestampSeconds = now

        val callbacks = active?.callbacks
        if (callbacks != null) {
            callbacks.onProgress(downloadedBytes, totalBytes)
        }
    }

    private fun resumeOffset(downloadTask: NSURLSessionDownloadTask): Long {
        val rangeHeader = downloadTask.originalRequest?.valueForHTTPHeaderField("Range") ?: return 0L
        return rangeHeader.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
    }

    private fun buildRequest(request: DownloadPlatformRequest, rangeStart: Long?): NSMutableURLRequest {
        val nativeRequest = NSMutableURLRequest(
            uRL = NSURL(string = request.sourceUrl),
            cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
            timeoutInterval = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS,
        )
        nativeRequest.setHTTPMethod("GET")
        request.sourceHeaders.forEach { (key, value) -> nativeRequest.setValue(value, forHTTPHeaderField = key) }
        if (rangeStart != null && rangeStart > 0L) {
            nativeRequest.setValue("bytes=$rangeStart-", forHTTPHeaderField = "Range")
        }
        return nativeRequest
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun appendFile(fromPath: String, toPath: String, append: Boolean): Boolean {
    if (!append) {
        return NSFileManager.defaultManager.moveItemAtPath(fromPath, toPath, null)
    }
    return copyByAppending(fromPath, toPath)
}

@OptIn(ExperimentalForeignApi::class)
private fun copyByAppending(fromPath: String, toPath: String): Boolean {
    val input = fopen(fromPath, "rb") ?: return false
    val output = fopen(toPath, "ab")
    if (output == null) {
        fclose(input)
        return false
    }

    val ok = runCatching {
        val buffer = ByteArray(64 * 1024)
        buffer.usePinned { pinned ->
            while (true) {
                val read = fread(pinned.addressOf(0), 1uL, buffer.size.convert(), input).toInt()
                if (read <= 0) break
                fwrite(pinned.addressOf(0), 1uL, read.convert(), output)
            }
        }
        true
    }.getOrDefault(false)

    fclose(input)
    fclose(output)
    if (ok) {
        NSFileManager.defaultManager.removeItemAtPath(fromPath, null)
    }
    return ok
}

internal data class DownloadsBaseDirectory(val path: String, val scopedUrl: NSURL?)

@OptIn(ExperimentalForeignApi::class)
private fun downloadsDirectoryPath(): String {
    val root = NSHomeDirectory().trimEnd('/')
    val path = "$root/Documents/nuvio_downloads"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

@OptIn(ExperimentalForeignApi::class)
internal fun resolveDownloadsBaseDirectory(): DownloadsBaseDirectory {
    val bookmarkBase64 = DownloadsSettingsRepository.downloadLocationUri.value
    val resolvedUrl = bookmarkBase64?.let(::resolveDownloadLocationBookmark)
    val resolvedPath = resolvedUrl?.path
    return if (resolvedUrl != null && resolvedPath != null) {
        DownloadsBaseDirectory(resolvedPath, resolvedUrl)
    } else {
        DownloadsBaseDirectory(downloadsDirectoryPath(), null)
    }
}

internal fun <T> DownloadsBaseDirectory.withAccess(block: (String) -> T): T {
    val started = scopedUrl?.startAccessingSecurityScopedResource() ?: false
    try {
        return block(path)
    } finally {
        if (started) scopedUrl?.stopAccessingSecurityScopedResource()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun removePathIfExists(path: String): Boolean {
    if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return true
    return NSFileManager.defaultManager.removeItemAtPath(path, null)
}

@OptIn(ExperimentalForeignApi::class)
internal fun fileSizeOrNull(path: String): Long? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
    val value = attrs?.get("NSFileSize")
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}

private fun String.toLocalPath(): String? {
    val value = trim()
    if (value.startsWith("file:")) {
        return NSURL(string = value).path ?: value.removePrefix("file://")
    }
    return value.takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalForeignApi::class)
private fun resumeDataDirectoryPath(): String {
    val path = "${NSHomeDirectory().trimEnd('/')}/Library/Application Support/nuvio_download_resume"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = path,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return path
}

private fun resumeDataPath(fileName: String): String =
    "${resumeDataDirectoryPath()}/$fileName.resumedata"

@OptIn(ExperimentalForeignApi::class)
private fun writeResumeData(fileName: String, data: NSData) {
    data.writeToFile(resumeDataPath(fileName), atomically = true)
}

@OptIn(ExperimentalForeignApi::class)
private fun takeResumeData(fileName: String): NSData? {
    val path = resumeDataPath(fileName)
    val data = NSData.dataWithContentsOfFile(path)
    removePathIfExists(path)
    return data?.takeIf { it.length > 0uL }
}
