package com.nuvio.app.core.automation

import com.nuvio.app.features.downloads.DownloadDiskInfo
import java.io.File

internal actual fun writeAutomationDownloadFile(fileName: String, bytes: ByteArray): String? {
    if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\')) return null
    val directory = DownloadDiskInfo.downloadsDirectoryOrNull() ?: return null
    val file = File(directory, fileName)
    if (!file.isFile || file.length() != bytes.size.toLong()) {
        file.writeBytes(bytes)
    }
    return file.toURI().toString()
}

internal actual fun writeAutomationBytes(fileName: String, bytes: ByteArray): String {
    val directory = File(System.getProperty("java.io.tmpdir"), "nuvio-automation")
    directory.mkdirs()
    val file = File(directory, fileName)
    if (!file.isFile || file.length() != bytes.size.toLong()) {
        file.writeBytes(bytes)
    }
    return file.toURI().toString()
}
