package com.nuvio.app.features.analysis

/**
 * Not wired on Android yet: the analyzer is iOS only, so nothing is stored.
 * Host tests set [retainForTests] so a saved analysis can be read back in-process.
 */
internal actual object MediaAnalysisFiles {
    internal var retainForTests: Boolean = false
    private val text = mutableMapOf<String, String>()
    private val binary = mutableMapOf<String, ByteArray>()

    internal fun clearForTests() {
        text.clear()
        binary.clear()
    }

    private fun jsonKey(name: String) = if (name.endsWith(".json")) name else "$name.json"

    actual fun read(name: String): String? = if (retainForTests) text[jsonKey(name)] else null

    actual fun write(name: String, text: String) {
        if (retainForTests) this.text[jsonKey(name)] = text
    }

    actual fun list(prefix: String): List<String> {
        if (!retainForTests) return emptyList()
        return text.keys.filter { it.startsWith(prefix) && it.endsWith(".json") }.map { it.removeSuffix(".json") }
    }

    actual fun binaryPath(fileName: String): String? = null

    actual fun readBytes(fileName: String): ByteArray? = if (retainForTests) binary[fileName] else null

    actual fun delete(fileName: String) {
        if (!retainForTests) return
        text.remove(fileName)
        text.remove(jsonKey(fileName))
        binary.remove(fileName)
    }

    actual fun writeBytes(fileName: String, bytes: ByteArray): Boolean {
        if (!retainForTests || bytes.isEmpty()) return false
        binary[fileName] = bytes
        return true
    }
}
