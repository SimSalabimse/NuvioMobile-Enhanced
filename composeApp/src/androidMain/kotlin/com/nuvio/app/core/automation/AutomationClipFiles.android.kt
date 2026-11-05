package com.nuvio.app.core.automation

import java.io.File

internal actual fun writeAutomationBytes(fileName: String, bytes: ByteArray): String {
    val directory = File(System.getProperty("java.io.tmpdir"), "nuvio-automation")
    directory.mkdirs()
    val file = File(directory, fileName)
    if (!file.isFile || file.length() != bytes.size.toLong()) {
        file.writeBytes(bytes)
    }
    return file.toURI().toString()
}
