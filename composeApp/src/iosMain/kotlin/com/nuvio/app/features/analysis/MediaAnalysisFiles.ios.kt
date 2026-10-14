package com.nuvio.app.features.analysis

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
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
}
