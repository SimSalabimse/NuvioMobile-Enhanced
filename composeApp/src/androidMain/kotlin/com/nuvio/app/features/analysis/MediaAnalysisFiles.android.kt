package com.nuvio.app.features.analysis

/** Not wired on Android yet: the analyzer is iOS only, so nothing is stored. */
internal actual object MediaAnalysisFiles {
    actual fun read(name: String): String? = null
    actual fun write(name: String, text: String) = Unit
    actual fun list(prefix: String): List<String> = emptyList()
    actual fun binaryPath(fileName: String): String? = null
    actual fun readBytes(fileName: String): ByteArray? = null
    actual fun delete(fileName: String) = Unit
}
