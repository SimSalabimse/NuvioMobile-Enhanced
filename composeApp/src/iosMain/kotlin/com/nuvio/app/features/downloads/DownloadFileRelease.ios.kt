package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory

/**
 * Implemented in Swift (HeldFileRelease.swift): closes the seek-preview readers, stops an analysis
 * run and the audio capture, and stops a player that has [path] open. iOS frees a deleted file's
 * space only when its last handle closes, so these used to keep gigabytes in use until a restart.
 */
interface NuvioFileReleaseBridge {
    fun releaseFile(path: String)
}

object NuvioFileReleaseRegistry {
    internal var bridge: NuvioFileReleaseBridge? = null

    fun register(bridge: NuvioFileReleaseBridge) {
        this.bridge = bridge
    }
}

internal fun releaseHeldDownloadFile(path: String) {
    runCatching { NuvioFileReleaseRegistry.bridge?.releaseFile(path) }
}

/** System download temp files ("CFNetworkDownload_*.tmp"). */
internal fun isSystemDownloadTempFile(name: String): Boolean =
    name.startsWith("CFNetworkDownload_") && name.endsWith(".tmp")

/** Where the background session keeps the bytes of a running or paused task, and tmp/. */
@OptIn(ExperimentalForeignApi::class)
internal fun systemDownloadTempDirectories(): List<String> {
    val home = NSHomeDirectory().trimEnd('/')
    val bundleId = NSBundle.mainBundle.bundleIdentifier.orEmpty()
    val root = "$home/Library/Caches/com.apple.nsurlsessiond/Downloads"
    val dirs = mutableListOf("$home/tmp")
    if (bundleId.isNotBlank()) dirs += "$root/$bundleId"
    // Some iOS versions nest the folder once more; list whatever is there.
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null).orEmpty()
        .mapNotNull { it as? String }
        .forEach { dirs += "$root/$it" }
    return dirs.distinct()
}
