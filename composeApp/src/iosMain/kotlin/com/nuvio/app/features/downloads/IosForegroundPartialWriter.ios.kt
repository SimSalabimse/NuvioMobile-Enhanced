package com.nuvio.app.features.downloads

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_finalize_file_failed
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite
import platform.Foundation.setValue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import platform.darwin.NSObject

private const val FOREGROUND_REQUEST_TIMEOUT_SECONDS = 120.0
private const val FOREGROUND_WRITE_BUFFER_BYTES = 1024 * 1024

/** Seconds of run time held after leaving the foreground so every handoff reaches nsurlsessiond. */
private const val HANDOFF_BACKGROUND_TIME_SECONDS = 10L

/**
 * On return to the app, a handed-off file comes back to the `.part` writer (early play follows
 * the bytes again) only while its background task has fetched at most this much, because the
 * background task's bytes are dropped on take-back.
 */
internal const val TAKE_BACK_MAX_BACKGROUND_BYTES = 8L * 1024L * 1024L

private enum class ForegroundMode { Running, UserCancel, Handoff }

@OptIn(ExperimentalForeignApi::class)
private class ForegroundJob(
    val request: DownloadPlatformRequest,
    var callbacks: DownloadCallbacks,
    val base: DownloadsBaseDirectory,
    var accessHeld: Boolean,
    var task: NSURLSessionDataTask? = null,
    var partPath: String = "",
    var file: CPointer<FILE>? = null,
    val buffer: ByteArray = ByteArray(FOREGROUND_WRITE_BUFFER_BYTES),
    var buffered: Int = 0,
    var flushedBytes: Long = 0L,
    var totalBytes: Long? = null,
    var rangedRequest: Boolean = false,
    var retriedWithoutRange: Boolean = false,
    var ignoreBody: Boolean = false,
    var retryFromZero: Boolean = false,
    var completeDespiteError: Boolean = false,
    var failMessage: String? = null,
    var mode: ForegroundMode = ForegroundMode.Running,
    var handoff: ((Long) -> Unit)? = null,
    var handoffSent: Boolean = false,
)

/**
 * While the process is alive, download bytes straight into `{name}.part`.
 * A background session never writes that file at the same time.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosForegroundDownloads {
    private val delegate = IosForegroundSessionDelegate()
    private var activeObserver: Any? = null

    /** Set from willResignActive until didBecomeActive: new files go to the background session. */
    private var leavingForeground = false
    private val handedOff = mutableSetOf<String>()
    private var handoffBackgroundTask = UIBackgroundTaskInvalid

    fun ensureObserver() {
        if (activeObserver != null) return
        activeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            onAppBecameActive()
        }
    }

    fun isRunning(downloadId: String): Boolean = delegate.isRunning(downloadId)

    fun replaceCallbacks(downloadId: String, callbacks: DownloadCallbacks) {
        delegate.replaceCallbacks(downloadId, callbacks)
    }

    fun shouldUseForeground(): Boolean =
        InAppPlaybackKeepAlive.isPlaying() ||
            (!leavingForeground &&
                UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)

    /**
     * Called from willResignActive, while the app is still in the foreground. iOS treats a
     * background-session task created after the app is in the background as discretionary and
     * may hold it until the phone is charging on Wi-Fi, so every transfer has to be on the
     * background session before the app gets there: running `.part` writers hand off now, and
     * every queued file is submitted too (the session runs two per host and starts the rest
     * itself while locked, without waking the app).
     */
    fun prepareForBackground() {
        ensureObserver()
        if (InAppPlaybackKeepAlive.isPlaying()) return
        leavingForeground = true
        holdBackgroundTime()
        handedOff += delegate.handoffAll()
        DownloadsRepository.submitWaitingForBackground()
    }

    /** didEnterBackground: a fallback for any writer willResignActive did not reach. */
    fun onAppEnteredBackground() {
        prepareForBackground()
    }

    private fun onAppBecameActive() {
        leavingForeground = false
        endBackgroundTime()
        DownloadsRepository.restoreForegroundCap()
        takeBackCheapHandoffs()
        // Opening the app reattaches a live background task. It does not start another request.
        IosBackgroundDownloadCoordinator.reconcileAfterOpen()
    }

    /**
     * A file that was writing `.part` before a short trip out of the app (Control Center, a
     * banner, a quick lock) comes back to the `.part` writer when its background task has
     * fetched little. A longer background run stays on its task so those bytes are kept; it
     * lands in `.part` when the task finishes.
     */
    private fun takeBackCheapHandoffs() {
        val ids = handedOff.toList()
        handedOff.clear()
        ids.forEach { downloadId ->
            val received = IosBackgroundDownloadCoordinator.bytesReceivedByTask(downloadId) ?: return@forEach
            if (received > TAKE_BACK_MAX_BACKGROUND_BYTES) return@forEach
            val captured = IosBackgroundDownloadCoordinator.peek(downloadId) ?: return@forEach
            val (request, callbacks) = captured
            if (callbacks == null) return@forEach
            IosBackgroundDownloadCoordinator.stopKeepingPartial(downloadId) {
                if (IosTransferControl.isSuppressed(downloadId)) return@stopKeepingPartial
                if (shouldUseForeground() && start(request, callbacks)) return@stopKeepingPartial
                IosBackgroundDownloadCoordinator.startOrResume(
                    downloadId = downloadId,
                    request = request,
                    rangeStart = iosPartialBytesOnDisk(request.destinationFileName),
                    callbacks = callbacks,
                )
            }
        }
    }

    private fun holdBackgroundTime() {
        if (handoffBackgroundTask != UIBackgroundTaskInvalid) return
        val application = UIApplication.sharedApplication
        handoffBackgroundTask = application.beginBackgroundTaskWithName("NuvioDownloadsHandoff") {
            endBackgroundTime()
        }
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, HANDOFF_BACKGROUND_TIME_SECONDS * 1_000_000_000L),
            dispatch_get_main_queue(),
        ) {
            endBackgroundTime()
        }
    }

    private fun endBackgroundTime() {
        val task = handoffBackgroundTask
        if (task == UIBackgroundTaskInvalid) return
        handoffBackgroundTask = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(task)
    }

    fun notePlayback(playing: Boolean) {
        val wasPlaying = InAppPlaybackKeepAlive.isPlaying()
        InAppPlaybackKeepAlive.setPlaying(playing)
        if (!wasPlaying && playing && !isApplicationActive()) {
            takeBackFromBackground()
            return
        }
        if (wasPlaying && !playing && !isApplicationActive()) {
            handedOff += delegate.handoffAll()
        }
    }

    fun start(request: DownloadPlatformRequest, callbacks: DownloadCallbacks): Boolean =
        delegate.start(request, callbacks)

    fun cancel(downloadId: String): Boolean = delegate.cancel(downloadId)

    /**
     * Stops the foreground writer and invokes [onReady] with the `.part` size
     * only after that file is closed. Returns false when no foreground writer exists.
     */
    fun handoffToBackground(downloadId: String, onReady: (Long) -> Unit): Boolean =
        delegate.handoff(downloadId, onReady)

    private fun takeBackFromBackground() {
        if (!shouldUseForeground()) return
        IosBackgroundDownloadCoordinator.activeDownloadIds().forEach { downloadId ->
            val captured = IosBackgroundDownloadCoordinator.peek(downloadId) ?: return@forEach
            IosBackgroundDownloadCoordinator.stopKeepingPartial(downloadId) {
                if (IosTransferControl.isSuppressed(downloadId)) return@stopKeepingPartial
                val (request, callbacks) = captured
                if (callbacks == null) return@stopKeepingPartial
                if (shouldUseForeground() && start(request, callbacks)) return@stopKeepingPartial
                IosBackgroundDownloadCoordinator.startOrResume(
                    downloadId = downloadId,
                    request = request,
                    rangeStart = iosPartialBytesOnDisk(request.destinationFileName),
                    callbacks = callbacks,
                )
            }
        }
    }

    private fun isApplicationActive(): Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
}

internal fun noteInAppPlaybackPlaying(playing: Boolean) {
    IosForegroundDownloads.notePlayback(playing)
}

internal object IosTransferControl {
    private val lock = NSLock()
    private val suppressed = mutableSetOf<String>()

    fun suppress(downloadId: String) {
        lock.lock()
        suppressed += downloadId
        lock.unlock()
    }

    fun allow(downloadId: String) {
        lock.lock()
        suppressed -= downloadId
        lock.unlock()
    }

    fun isSuppressed(downloadId: String): Boolean {
        lock.lock()
        val value = downloadId in suppressed
        lock.unlock()
        return value
    }
}

/**
 * Retries after a dropped connection, timeout or lost network (see [DownloadRetryPolicy]),
 * resuming from the bytes already on disk instead of marking the download Failed.
 */
internal object IosDownloadRetries {
    private val lock = NSLock()
    private val tracker = DownloadRetryTracker()

    private fun nextDelay(downloadId: String, bytesOnDisk: Long): Long? {
        lock.lock()
        val delay = tracker.nextDelaySeconds(downloadId, bytesOnDisk)
        lock.unlock()
        return delay
    }

    fun forget(downloadId: String) {
        lock.lock()
        tracker.forget(downloadId)
        lock.unlock()
    }

    /** Only a row that is still meant to be downloading comes back. */
    fun stillWanted(downloadId: String): Boolean =
        !IosTransferControl.isSuppressed(downloadId) &&
            DownloadsRepository.uiState.value.items.any {
                it.id == downloadId && it.status == DownloadStatus.Downloading
            }

    /**
     * The `.part` writer lost its connection. In the app: try the writer again after the backoff,
     * with a Range from the bytes on disk. Leaving or out of the app: hand the file to the
     * background session now, which waits for the network itself. Returns false when the
     * retries are used up and the caller should report the failure.
     */
    fun scheduleForegroundRetry(request: DownloadPlatformRequest, callbacks: DownloadCallbacks): Boolean {
        val downloadId = request.item.id
        val onDisk = iosPartialBytesOnDisk(request.destinationFileName)
        val delay = nextDelay(downloadId, onDisk) ?: return false
        if (!IosForegroundDownloads.shouldUseForeground()) {
            dispatch_async(dispatch_get_main_queue()) {
                if (!stillWanted(downloadId)) return@dispatch_async
                IosBackgroundDownloadCoordinator.startOrResume(
                    downloadId = downloadId,
                    request = request,
                    rangeStart = iosPartialBytesOnDisk(request.destinationFileName),
                    callbacks = callbacks,
                )
            }
            return true
        }
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, delay * 1_000_000_000L),
            dispatch_get_main_queue(),
        ) {
            if (!stillWanted(downloadId)) return@dispatch_after
            if (IosForegroundDownloads.shouldUseForeground() && IosForegroundDownloads.start(request, callbacks)) {
                return@dispatch_after
            }
            IosBackgroundDownloadCoordinator.startOrResume(
                downloadId = downloadId,
                request = request,
                rangeStart = iosPartialBytesOnDisk(request.destinationFileName),
                callbacks = callbacks,
            )
        }
        return true
    }

    /**
     * The background task lost its connection. A new task resumes from the system's resume data
     * when there is some, otherwise with a Range from `.part`. The backoff is the task's
     * earliest begin date, which the session honours while the app is suspended.
     * Returns the delay, or null when the retries are used up.
     */
    fun backgroundRetryDelay(downloadId: String, bytesOnDisk: Long): Long? = nextDelay(downloadId, bytesOnDisk)
}

internal object IosDeferredPartialRename {
    private val lock = NSLock()
    private val pending = mutableMapOf<String, String>()

    fun remember(downloadId: String, fileName: String) {
        lock.lock()
        pending[downloadId] = fileName
        lock.unlock()
    }

    fun take(downloadId: String): String? {
        lock.lock()
        val fileName = pending.remove(downloadId)
        lock.unlock()
        return fileName
    }

    fun clearFileName(fileName: String) {
        lock.lock()
        pending.entries.removeAll { it.value == fileName }
        lock.unlock()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun iosPartialBytesOnDisk(fileName: String): Long =
    resolveDownloadsBaseDirectory().withAccess { directory ->
        val partial = "$directory/$fileName.part"
        if (NSFileManager.defaultManager.fileExistsAtPath(partial)) {
            fileSizeOrNull(partial) ?: 0L
        } else {
            fileSizeOrNull("$directory/$fileName") ?: 0L
        }
    }

/** Length of the `.part` file only. A finished file is not a resume prefix. */
@OptIn(ExperimentalForeignApi::class)
internal fun iosPartFileLength(fileName: String): Long {
    if (fileName.isBlank()) return 0L
    return resolveDownloadsBaseDirectory().withAccess { directory ->
        val partial = "$directory/$fileName.part"
        if (!NSFileManager.defaultManager.fileExistsAtPath(partial)) {
            0L
        } else {
            fileSizeOrNull(partial) ?: 0L
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun finalizeIosPartialFile(
    downloadId: String,
    fileName: String,
    callbacks: DownloadCallbacks?,
) {
    val failure = {
        callbacks?.onFailure(runBlocking { getString(Res.string.downloads_error_finalize_file_failed) })
    }
    val base = resolveDownloadsBaseDirectory()
    val started = base.scopedUrl?.startAccessingSecurityScopedResource() ?: false
    try {
        val part = "${base.path}/$fileName.part"
        val destination = "${base.path}/$fileName"
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(part)) {
            if (manager.fileExistsAtPath(destination)) {
                callbacks?.onSuccess(fileUri(destination), fileSizeOrNull(destination))
            } else {
                failure()
            }
            return
        }
        if (PartialPlaybackLease.isHeld(downloadId)) {
            IosDeferredPartialRename.remember(downloadId, fileName)
            callbacks?.onSuccess(fileUri(destination), fileSizeOrNull(part))
            return
        }
        removePathIfExists(destination)
        if (!manager.moveItemAtPath(part, destination, null)) {
            failure()
            return
        }
        callbacks?.onSuccess(fileUri(destination), fileSizeOrNull(destination))
    } finally {
        if (started) base.scopedUrl?.stopAccessingSecurityScopedResource()
        IosWriterOwnership.release(downloadId, DownloadWriterKind.Foreground)
        IosWriterOwnership.release(downloadId, DownloadWriterKind.Background)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun finishIosPartialPlayback(downloadId: String): String? {
    val fileName = IosDeferredPartialRename.take(downloadId) ?: return null
    val base = resolveDownloadsBaseDirectory()
    val started = base.scopedUrl?.startAccessingSecurityScopedResource() ?: false
    try {
        val part = "${base.path}/$fileName.part"
        val destination = "${base.path}/$fileName"
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(part)) {
            return if (manager.fileExistsAtPath(destination)) fileUri(destination) else {
                IosDeferredPartialRename.remember(downloadId, fileName)
                null
            }
        }
        removePathIfExists(destination)
        if (!manager.moveItemAtPath(part, destination, null)) {
            IosDeferredPartialRename.remember(downloadId, fileName)
            return null
        }
        return fileUri(destination)
    } finally {
        if (started) base.scopedUrl?.stopAccessingSecurityScopedResource()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosForegroundSessionDelegate : NSObject(), NSURLSessionDataDelegateProtocol {
    private val lock = NSLock()
    private val jobs = mutableMapOf<String, ForegroundJob>()
    private var session: NSURLSession? = null

    fun start(request: DownloadPlatformRequest, callbacks: DownloadCallbacks): Boolean {
        val downloadId = request.item.id
        if (IosTransferControl.isSuppressed(downloadId)) return false
        lock.lock()
        val existing = jobs[downloadId]
        if (existing != null && existing.mode == ForegroundMode.Running) {
            existing.callbacks = callbacks
            lock.unlock()
            return true
        }
        lock.unlock()
        if (!IosWriterOwnership.acquire(downloadId, DownloadWriterKind.Foreground)) return false

        val base = resolveDownloadsBaseDirectory()
        val scopedStarted = base.scopedUrl?.startAccessingSecurityScopedResource() ?: false
        val job = ForegroundJob(
            request = request,
            callbacks = callbacks,
            base = base,
            accessHeld = scopedStarted,
        )
        val partPath = "${base.path}/${request.destinationFileName}.part"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = base.path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        if (!NSFileManager.defaultManager.fileExistsAtPath(partPath)) {
            NSFileManager.defaultManager.createFileAtPath(partPath, contents = null, attributes = null)
        }
        val file = fopen(partPath, "ab")
        if (file == null) {
            if (scopedStarted) base.scopedUrl?.stopAccessingSecurityScopedResource()
            IosWriterOwnership.release(downloadId, DownloadWriterKind.Foreground)
            return false
        }
        val existingBytes = fileSizeOrNull(partPath) ?: 0L
        job.partPath = partPath
        job.file = file
        job.flushedBytes = existingBytes
        job.rangedRequest = existingBytes > 0L
        IosBackgroundDownloadCoordinator.discardResumeData(request.destinationFileName)

        lock.lock()
        if (IosTransferControl.isSuppressed(downloadId) || jobs[downloadId] != null) {
            lock.unlock()
            closeHandle(job)
            IosWriterOwnership.release(downloadId, DownloadWriterKind.Foreground)
            return false
        }
        jobs[downloadId] = job
        val task = session().dataTaskWithRequest(buildForegroundRequest(request, existingBytes.takeIf { it > 0L }))
        task.taskDescription = downloadId
        job.task = task
        lock.unlock()
        if (IosTransferControl.isSuppressed(downloadId)) {
            cancel(downloadId)
            task.cancel()
            return true
        }
        task.resume()
        callbacks.onProgress(existingBytes, null)
        return true
    }

    fun cancel(downloadId: String): Boolean {
        lock.lock()
        val job = jobs[downloadId]
        if (job == null) {
            lock.unlock()
            return false
        }
        if (job.mode == ForegroundMode.Handoff) {
            lock.unlock()
            return true
        }
        job.mode = ForegroundMode.UserCancel
        flushLocked(job)
        closeHandle(job)
        val task = job.task
        jobs.remove(downloadId)
        lock.unlock()
        IosBackgroundDownloadCoordinator.discardResumeData(job.request.destinationFileName)
        IosWriterOwnership.release(downloadId, DownloadWriterKind.Foreground)
        task?.cancel()
        return true
    }

    fun handoff(downloadId: String, onReady: (Long) -> Unit): Boolean {
        lock.lock()
        val job = jobs[downloadId]
        if (job == null || job.mode != ForegroundMode.Running) {
            lock.unlock()
            return false
        }
        job.mode = ForegroundMode.Handoff
        job.handoff = onReady
        flushLocked(job)
        closeHandle(job)
        val task = job.task
        lock.unlock()
        // The `.part` handle is closed and this job ignores any further body, so the background
        // task starts now rather than after the cancelled task reports back. That callback can
        // arrive after the app is already in the background, which makes the new task discretionary.
        task?.cancel()
        deliverHandoff(downloadId)
        return true
    }

    fun isRunning(downloadId: String): Boolean {
        lock.lock()
        val running = jobs[downloadId]?.mode == ForegroundMode.Running
        lock.unlock()
        return running
    }

    fun replaceCallbacks(downloadId: String, callbacks: DownloadCallbacks) {
        lock.lock()
        val job = jobs[downloadId]
        if (job != null) job.callbacks = callbacks
        lock.unlock()
    }

    /** Hands every running writer to the background session. Returns the ids handed off. */
    fun handoffAll(): List<String> {
        lock.lock()
        val ids = jobs.keys.toList()
        lock.unlock()
        val handed = mutableListOf<String>()
        ids.forEach { downloadId ->
            val captured = lock.withLock {
                val job = jobs[downloadId] ?: return@withLock null
                job.request to job.callbacks
            } ?: return@forEach
            val started = handoff(downloadId) { size ->
                if (IosTransferControl.isSuppressed(downloadId)) return@handoff
                IosBackgroundDownloadCoordinator.startOrResume(
                    downloadId = downloadId,
                    request = captured.first,
                    rangeStart = size,
                    callbacks = captured.second,
                )
            }
            if (started) handed += downloadId
        }
        return handed
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        val http = didReceiveResponse as? NSHTTPURLResponse
        val status = http?.statusCode?.toInt() ?: 0
        lock.lock()
        val job = jobs[dataTask.taskDescription.orEmpty()]
        if (job == null || job.mode != ForegroundMode.Running || job.task?.taskIdentifier != dataTask.taskIdentifier) {
            lock.unlock()
            completionHandler(NSURLSessionResponseAllow)
            return
        }
        when {
            status == 416 && job.flushedBytes > 0L -> {
                job.ignoreBody = true
                job.completeDespiteError = true
            }
            status == 416 && !job.retriedWithoutRange -> {
                job.ignoreBody = true
                job.retryFromZero = true
            }
            status == 416 || status !in 200..299 -> {
                job.ignoreBody = true
                job.failMessage = if (DownloadLinkFailure.isRefreshable(status)) {
                    // Expired or session-bound link: the repository fetches a fresh one and resumes.
                    IosBackgroundDownloadCoordinator.discardResumeData(job.request.destinationFileName)
                    DownloadLinkFailure.encode(status)
                } else {
                    httpFailure(status)
                }
            }
            // A ranged answer that does not continue the bytes on disk (another offset, file or
            // size): ignore it and fetch the file again from byte 0.
            status == 206 && job.rangedRequest && partialPrefixDisposition(
                statusCode = status,
                requestedRange = job.flushedBytes,
                prefixLength = job.flushedBytes,
                contentRange = http?.let { headerValue(it, "Content-Range") },
                expectedTotal = DownloadsRepository.knownTotalBytes(job.request.item.id),
            ) == PartialPrefixDisposition.Restart -> {
                job.ignoreBody = true
                job.retryFromZero = true
            }
            else -> {
                if (status == 200 && job.rangedRequest) {
                    val disposition = partialPrefixDisposition(
                        statusCode = status,
                        requestedRange = job.flushedBytes,
                        prefixLength = job.flushedBytes,
                    )
                    if (disposition == PartialPrefixDisposition.Keep) {
                        job.ignoreBody = true
                        job.failMessage = httpFailure(status)
                    } else {
                        job.buffered = 0
                        job.flushedBytes = 0L
                        job.rangedRequest = false
                        if (!reopenTruncated(job)) {
                            job.ignoreBody = true
                            job.failMessage = runBlocking {
                                getString(Res.string.downloads_error_finalize_file_failed)
                            }
                        }
                    }
                }
                if (job.failMessage != null) {
                    lock.unlock()
                    completionHandler(NSURLSessionResponseAllow)
                    return
                }
                job.totalBytes = when (status) {
                    206 -> contentRangeTotal(http?.let { headerValue(it, "Content-Range") })
                        ?: http?.expectedContentLength?.takeIf { it > 0L }?.let { job.flushedBytes + it }
                    else -> http?.expectedContentLength?.takeIf { it > 0L }
                }
                job.callbacks.onProgress(job.flushedBytes, job.totalBytes)
            }
        }
        lock.unlock()
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        val bytes = didReceiveData.copyBytes()
        if (bytes.isEmpty()) return
        lock.lock()
        val job = jobs[dataTask.taskDescription.orEmpty()]
        if (
            job == null ||
            job.mode != ForegroundMode.Running ||
            job.ignoreBody ||
            job.task?.taskIdentifier != dataTask.taskIdentifier
        ) {
            lock.unlock()
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val space = job.buffer.size - job.buffered
            val count = minOf(space, bytes.size - offset)
            bytes.copyInto(job.buffer, job.buffered, offset, offset + count)
            job.buffered += count
            offset += count
            if (job.buffered >= job.buffer.size || crossedEarlyPlay(job)) {
                flushLocked(job)
            }
        }
        lock.unlock()
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        val downloadId = task.taskDescription ?: return
        lock.lock()
        val job = jobs[downloadId]
        if (job == null || job.task?.taskIdentifier != task.taskIdentifier) {
            lock.unlock()
            return
        }
        when (job.mode) {
            ForegroundMode.UserCancel -> {
                jobs.remove(downloadId)
                lock.unlock()
                return
            }
            ForegroundMode.Handoff -> {
                lock.unlock()
                deliverHandoff(downloadId)
                return
            }
            ForegroundMode.Running -> Unit
        }
        if (job.retryFromZero) {
            job.retryFromZero = false
            job.retriedWithoutRange = true
            job.ignoreBody = false
            job.failMessage = null
            job.rangedRequest = false
            job.buffered = 0
            job.flushedBytes = 0L
            if (!reopenTruncated(job)) {
                val callbacks = job.callbacks
                closeHandle(job)
                jobs.remove(downloadId)
                lock.unlock()
                IosWriterOwnership.release(downloadId, DownloadWriterKind.Foreground)
                callbacks.onFailure(
                    runBlocking { getString(Res.string.downloads_error_finalize_file_failed) },
                )
                return
            }
            val replacement = session().dataTaskWithRequest(buildForegroundRequest(job.request, null))
            replacement.taskDescription = downloadId
            job.task = replacement
            lock.unlock()
            if (!IosTransferControl.isSuppressed(downloadId)) replacement.resume()
            return
        }
        val callbacks = job.callbacks
        val request = job.request
        val failMessage = job.failMessage
        val completeDespiteError = job.completeDespiteError
        flushLocked(job)
        closeHandle(job)
        jobs.remove(downloadId)
        lock.unlock()
        if ((didCompleteWithError != null || failMessage != null) && !completeDespiteError) {
            IosWriterOwnership.release(downloadId, DownloadWriterKind.Foreground)
            val error = didCompleteWithError
            if (
                failMessage == null &&
                error != null &&
                DownloadRetryPolicy.isTransient(error.domain, error.code) &&
                IosDownloadRetries.scheduleForegroundRetry(request, callbacks)
            ) {
                return
            }
            IosDownloadRetries.forget(downloadId)
            callbacks.onFailure(failMessage ?: didCompleteWithError?.localizedDescription ?: httpFailure(0))
            return
        }
        IosDownloadRetries.forget(downloadId)
        finalizeIosPartialFile(downloadId, request.destinationFileName, callbacks)
    }

    private fun deliverHandoff(downloadId: String) {
        lock.lock()
        val job = jobs[downloadId] ?: run {
            lock.unlock()
            return
        }
        if (job.handoffSent) {
            lock.unlock()
            return
        }
        job.handoffSent = true
        val callback = job.handoff
        val fileName = job.request.destinationFileName
        jobs.remove(downloadId)
        lock.unlock()
        IosWriterOwnership.release(downloadId, DownloadWriterKind.Foreground)
        if (IosTransferControl.isSuppressed(downloadId)) return
        callback?.invoke(iosPartialBytesOnDisk(fileName))
    }

    private fun session(): NSURLSession {
        session?.let { return it }
        val configuration = NSURLSessionConfiguration.defaultSessionConfiguration()
        configuration.timeoutIntervalForRequest = FOREGROUND_REQUEST_TIMEOUT_SECONDS
        configuration.timeoutIntervalForResource = DOWNLOAD_RESOURCE_TIMEOUT_SECONDS
        configuration.allowsCellularAccess = true
        val created = NSURLSession.sessionWithConfiguration(
            configuration = configuration,
            delegate = this,
            delegateQueue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 },
        )
        session = created
        return created
    }

    private fun flushLocked(job: ForegroundJob) {
        if (job.buffered <= 0) return
        val file = job.file ?: return
        val count = job.buffered
        val written = job.buffer.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1uL, count.convert(), file).toInt()
        }
        // fflush publishes bytes to other readers. It is not an fsync.
        fflush(file)
        if (written in 1 until count) {
            job.buffer.copyInto(job.buffer, 0, written, count)
        }
        job.buffered = if (written >= count) 0 else (count - written).coerceAtLeast(0)
        if (written > 0) {
            job.flushedBytes += written
            job.callbacks.onProgress(job.flushedBytes, job.totalBytes)
        }
    }

    private fun reopenTruncated(job: ForegroundJob): Boolean {
        job.file?.let { fclose(it) }
        job.file = null
        val path = job.partPath
        if (path.isEmpty()) return false
        val reopened = fopen(path, "wb") ?: return false
        job.file = reopened
        return true
    }

    private fun closeHandle(job: ForegroundJob) {
        job.file?.let { fclose(it) }
        job.file = null
        if (job.accessHeld) {
            job.base.scopedUrl?.stopAccessingSecurityScopedResource()
            job.accessHeld = false
        }
    }

    private fun crossedEarlyPlay(job: ForegroundJob): Boolean =
        job.flushedBytes < EARLY_PLAY_BYTES && job.flushedBytes + job.buffered >= EARLY_PLAY_BYTES

    private fun <T> NSLock.withLock(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun buildForegroundRequest(request: DownloadPlatformRequest, rangeStart: Long?): NSMutableURLRequest {
    val nativeRequest = NSMutableURLRequest(
        uRL = NSURL(string = request.sourceUrl),
        cachePolicy = NSURLRequestReloadIgnoringLocalCacheData,
        timeoutInterval = FOREGROUND_REQUEST_TIMEOUT_SECONDS,
    )
    nativeRequest.setHTTPMethod("GET")
    request.sourceHeaders.forEach { (key, value) ->
        nativeRequest.setValue(value, forHTTPHeaderField = key)
    }
    if (rangeStart != null && rangeStart > 0L) {
        nativeRequest.setValue("bytes=$rangeStart-", forHTTPHeaderField = "Range")
    }
    return nativeRequest
}

private fun contentRangeTotal(header: String?): Long? {
    if (header.isNullOrBlank()) return null
    return header.substringAfter('/', "").trim().toLongOrNull()
}

@OptIn(ExperimentalForeignApi::class)
internal fun headerValue(response: NSHTTPURLResponse, name: String): String? {
    val fields = response.allHeaderFields
    for (key in fields.keys) {
        if (key?.toString()?.equals(name, ignoreCase = true) == true) {
            return fields[key]?.toString()
        }
    }
    return null
}

private fun httpFailure(status: Int): String =
    runBlocking { getString(Res.string.network_request_failed_http, status) }

private fun fileUri(path: String): String =
    NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
