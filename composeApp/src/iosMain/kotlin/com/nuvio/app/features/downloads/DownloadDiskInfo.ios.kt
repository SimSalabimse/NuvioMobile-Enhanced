package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey
import platform.Foundation.NSURLVolumeTotalCapacityKey
import platform.Foundation.NSUserDefaults

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadDiskInfo {
    private const val PAYLOAD_KEY_PREFIX = "downloads_payload_"

    actual fun volumeSpace(): VolumeSpace? = resolveDownloadsBaseDirectory().withAccess { path ->
        val values = NSURL.fileURLWithPath(path).resourceValuesForKeys(
            listOf(NSURLVolumeAvailableCapacityForImportantUsageKey, NSURLVolumeTotalCapacityKey),
            null,
        )
        val important = (values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue
        val total = (values?.get(NSURLVolumeTotalCapacityKey) as? NSNumber)?.longLongValue
        val fsAttrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)
        val free = important?.takeIf { it > 0L } ?: (fsAttrs?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
        val size = total?.takeIf { it > 0L } ?: (fsAttrs?.get(NSFileSystemSize) as? NSNumber)?.longLongValue
        if (free == null || size == null || size <= 0L) null else VolumeSpace(free, size)
    }

    actual fun listDownloadFiles(): List<DiskFile> = resolveDownloadsBaseDirectory().withAccess { path ->
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)
            .orEmpty()
            .mapNotNull { it as? String }
            .filter { !it.startsWith(".") }
            .map { name -> DiskFile(name, entrySize("$path/$name")) }
    }

    actual fun deleteDownloadFile(name: String): Boolean {
        if (name.isBlank() || name.contains('/') || name == "." || name == "..") return false
        return resolveDownloadsBaseDirectory().withAccess { path -> removePathIfExists("$path/$name") }
    }

    actual fun allProfilePayloads(): List<String> {
        val defaults = NSUserDefaults.standardUserDefaults
        return defaults.dictionaryRepresentation().keys
            .mapNotNull { it as? String }
            .filter { it.startsWith(PAYLOAD_KEY_PREFIX) }
            .mapNotNull { defaults.stringForKey(it) }
    }

    private fun entrySize(path: String, depth: Int = 0): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return 0L
        if (attrs[NSFileType] == NSFileTypeDirectory) {
            if (depth > 3) return 0L
            return NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)
                .orEmpty()
                .mapNotNull { it as? String }
                .sumOf { entrySize("$path/$it", depth + 1) }
        }
        return (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: fileSizeOrNull(path) ?: 0L
    }
}
