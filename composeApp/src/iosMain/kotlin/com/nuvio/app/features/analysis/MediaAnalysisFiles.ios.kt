package com.nuvio.app.features.analysis

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual object MediaAnalysisFiles {
    private val directory: String? by lazy {
        val base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: return@lazy null
        val dir = "$base/NuvioAnalysis"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        dir
    }

    actual fun read(name: String): String? {
        val dir = directory ?: return null
        val path = "$dir/$name.json"
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        return NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
    }

    actual fun write(name: String, text: String) {
        val dir = directory ?: return
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ns = NSString.create(string = text)
        ns.writeToFile("$dir/$name.json", atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    actual fun list(prefix: String): List<String> {
        val dir = directory ?: return emptyList()
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null).orEmpty()
        return names.mapNotNull { it as? String }
            .filter { it.startsWith(prefix) && it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
    }

    actual fun binaryPath(fileName: String): String? = directory?.let { "$it/$fileName" }

    actual fun readBytes(fileName: String): ByteArray? {
        val path = binaryPath(fileName) ?: return null
        val data = platform.Foundation.NSData.create(contentsOfFile = path) ?: return null
        val size = data.length.toInt()
        if (size <= 0) return null
        val out = ByteArray(size)
        out.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return out
    }

    actual fun delete(fileName: String) {
        val path = binaryPath(fileName) ?: return
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    actual fun writeBytes(fileName: String, bytes: ByteArray): Boolean {
        val path = binaryPath(fileName) ?: return false
        if (bytes.isEmpty()) return false
        val data = bytes.usePinned { pinned ->
            platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        return data.writeToFile(path, atomically = true)
    }
}
