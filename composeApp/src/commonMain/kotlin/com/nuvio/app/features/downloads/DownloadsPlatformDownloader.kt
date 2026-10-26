package com.nuvio.app.features.downloads

internal data class DownloadPlatformRequest(
    val item: DownloadItem,
) {
    val sourceUrl: String get() = item.sourceUrl
    val sourceHeaders: Map<String, String> get() = item.sourceHeaders
    val destinationFileName: String get() = item.fileName
}

/** Result of a checked delete: [NotFound] (nothing anywhere) does not count as deleted. */
internal enum class FileDeleteOutcome { Deleted, NotFound, StillThere }

internal interface DownloadsTaskHandle {
    fun cancel()
}

/** The transfer calls [DownloadsRepository] makes; a test puts a fake platform layer here. */
internal interface DownloadTransfers {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle

    fun pauseRunningTransfer(item: DownloadItem)
}

internal object PlatformDownloadTransfers : DownloadTransfers {
    override fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle = DownloadsPlatformDownloader.start(request, onProgress, onSuccess, onFailure, onPaused)

    override fun pauseRunningTransfer(item: DownloadItem) = DownloadsPlatformDownloader.pauseRunningTransfer(item)
}

internal expect object DownloadsPlatformDownloader {
    fun start(
        request: DownloadPlatformRequest,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        onSuccess: (localFileUri: String, totalBytes: Long?) -> Unit,
        onFailure: (message: String) -> Unit,
        onPaused: () -> Unit,
    ): DownloadsTaskHandle

    fun restoreItem(item: DownloadItem): DownloadItem

    fun removeFile(localFileUri: String?): Boolean

    fun removePartialFile(destinationFileName: String): Boolean

    /** Deletes the finished file at any of [candidates] or by [fileName] in every downloads folder, then checks. */
    fun removeFileVerified(candidates: List<String?>, fileName: String): FileDeleteOutcome

    /** The row is going away: stop its transfer without keeping resume data. */
    fun discardTransfer(downloadId: String)

    fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String?

    fun openDownloadsDirectory(): Boolean

    fun readPartialPrefix(destinationFileName: String, maxBytes: Int): ByteArray

    /**
     * Bytes early play can read right now, or null when that is the reported progress.
     * On iOS a background task keeps its bytes in a system temp file until it finishes, so
     * `.part` can be behind the reported progress.
     */
    fun playableBytesOnDisk(destinationFileName: String): Long?

    fun updatePartialTarget(
        downloadId: String,
        fileName: String,
        totalBytes: Long?,
        downloadRunning: Boolean,
    )

    fun partialPlaybackUrl(downloadId: String): String?

    fun finishPartialPlayback(downloadId: String): String?

    fun pauseRunningTransfer(item: DownloadItem)
}
