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
import platform.darwin.NSObject

private const val FOREGROUND_REQUEST_TIMEOUT_SECONDS = 120.0
private const val FOREGROUND_WRITE_BUFFER_BYTES = 1024 * 1024

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

    fun ensureObserver() {
        if (activeObserver != null) return
        activeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            takeBackFromBackground()
        }
    }

    fun shouldUseForeground(): Boolean =
        InAppPlaybackKeepAlive.isPlaying() ||
            UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

    fun onAppEnteredBackground() {
        ensureObserver()
        if (InAppPlaybackKeepAlive.isPlaying()) return
        delegate.handoffAll()
    }

    fun notePlayback(playing: Boolean) {
        val wasPlaying = InAppPlaybackKeepAlive.isPlaying()
        InAppPlaybackKeepAlive.setPlaying(playing)
        if (!wasPlaying && playing && !isApplicationActive()) {
            takeBackFromBackground()
            return
        }
        if (wasPlaying && !playing && !isApplicationActive()) {
            delegate.handoffAll()
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
        if (task == null) {
            deliverHandoff(downloadId)
        } else {
            task.cancel()
        }
        return true
    }

    fun handoffAll() {
        lock.lock()
        val ids = jobs.keys.toList()
        lock.unlock()
        ids.forEach { downloadId ->
            val captured = lock.withLock {
                val job = jobs[downloadId] ?: return@withLock null
                job.request to job.callbacks
            } ?: return@forEach
            handoff(downloadId) { size ->
                if (IosTransferControl.isSuppressed(downloadId)) return@handoff
                IosBackgroundDownloadCoordinator.startOrResume(
                    downloadId = downloadId,
                    request = captured.first,
                    rangeStart = size,
                    callbacks = captured.second,
                )
            }
        }
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
            status == 416 -> {
                job.ignoreBody = true
                job.failMessage = httpFailure(status)
            }
            status !in 200..299 -> {
                job.ignoreBody = true
                job.failMessage = httpFailure(status)
            }
            else -> {
                if (status == 200 && job.rangedRequest) {
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
            callbacks.onFailure(failMessage ?: didCompleteWithError?.localizedDescription ?: httpFailure(0))
            return
        }
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
private fun headerValue(response: NSHTTPURLResponse, name: String): String? {
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
