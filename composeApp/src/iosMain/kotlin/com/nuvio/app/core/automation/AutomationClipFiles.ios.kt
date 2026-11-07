package com.nuvio.app.core.automation

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import com.nuvio.app.features.downloads.resolveDownloadsBaseDirectory
import com.nuvio.app.features.downloads.withAccess
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun writeAutomationDownloadFile(fileName: String, bytes: ByteArray): String? {
    if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\')) return null
    return resolveDownloadsBaseDirectory().withAccess { downloadsDirectory ->
        val path = "$downloadsDirectory/$fileName"
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        if (!data.writeToFile(path, atomically = true)) return@withAccess null
        NSURL.fileURLWithPath(path).absoluteString
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun writeAutomationBytes(fileName: String, bytes: ByteArray): String {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/nuvio-automation"
    NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
    val path = "$directory/$fileName"
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    data.writeToFile(path, atomically = true)
    return "file://$path"
}
