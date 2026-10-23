package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSByteCountFormatter
import platform.Foundation.NSByteCountFormatterCountStyleFile
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
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

    /**
     * Free = volumeAvailableCapacityForImportantUsage, total = volumeTotalCapacity: the figures
     * iOS Settings uses. Read for the downloads folder, then the app container, so a custom
     * folder that does not answer does not drop to the smaller plain free-size figure.
     */
    actual fun volumeSpace(): VolumeSpace? {
        val fromDownloads = resolveDownloadsBaseDirectory().withAccess { path -> capacity(path) }
        return fromDownloads ?: capacity(NSHomeDirectory())
    }

    private fun capacity(path: String): VolumeSpace? {
        val url = NSURL.fileURLWithPath(path)
        url.removeAllCachedResourceValues()
        val values = url.resourceValuesForKeys(
            listOf(NSURLVolumeAvailableCapacityForImportantUsageKey, NSURLVolumeTotalCapacityKey),
            null,
        )
        val important = values?.get(NSURLVolumeAvailableCapacityForImportantUsageKey).asLong()
        val total = values?.get(NSURLVolumeTotalCapacityKey).asLong()
        val fsAttrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null)
        val free = important?.takeIf { it > 0L } ?: fsAttrs?.get(NSFileSystemFreeSize).asLong()
        val size = total?.takeIf { it > 0L } ?: fsAttrs?.get(NSFileSystemSize).asLong()
        return if (free == null || size == null || size <= 0L) null else VolumeSpace(free, size)
    }

    private fun Any?.asLong(): Long? = when (this) {
        is NSNumber -> longLongValue
        is Long -> this
        is Number -> toLong()
        else -> null
    }

    actual fun appStorageUsage(ownedFileNames: Set<String>): AppStorageUsage? {
        val home = NSHomeDirectory().trimEnd('/')
        val containerBytes = treeSize(home)
        val cacheBytes = treeSize("$home/Library/Caches") + treeSize("$home/tmp")
        val downloadsPath = resolveDownloadsBaseDirectory().path.trimEnd('/')
        // A download folder chosen outside the app is not in the container walk.
        val outside = if (downloadsPath.startsWith("$home/")) 0L else {
            resolveDownloadsBaseDirectory().withAccess { treeSize(it) }
        }
        val leftovers = mutableListOf<LeftoverFile>()
        // Loose subtitle files ("3.srt", "manifest.json") at the Documents root or the downloads root.
        for (dir in listOf("$home/Documents", downloadsPath).distinct()) {
            NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
                .mapNotNull { it as? String }
                .filter { isLooseSubtitleFile(it) }
                .forEach { name -> leftovers += LeftoverFile("$dir/$name", entrySize("$dir/$name")) }
        }
        // Resume data of downloads that no longer exist.
        val resumeDir = "$home/Library/Application Support/nuvio_download_resume"
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(resumeDir, null).orEmpty()
            .mapNotNull { it as? String }
            .filter { it.endsWith(".resumedata") && it.removeSuffix(".resumedata") !in ownedFileNames }
            .forEach { name -> leftovers += LeftoverFile("$resumeDir/$name", entrySize("$resumeDir/$name")) }
        // The default folder while a custom one is active: files of deleted downloads left there.
        val defaultDir = downloadsDirectoryPath().trimEnd('/')
        if (defaultDir != downloadsPath) {
            NSFileManager.defaultManager.contentsOfDirectoryAtPath(defaultDir, null).orEmpty()
                .mapNotNull { it as? String }
                .filter { name -> isNuvioDownloadFileName(name) && ownedFileNames.none { ownsDiskFile(it, name) } }
                .forEach { name -> leftovers += LeftoverFile("$defaultDir/$name", entrySize("$defaultDir/$name")) }
        }
        // Bytes of background downloads the system kept (tmp/ and the nsurlsessiond cache), only
        // when nothing runs on the background session and no resume data points at them.
        IosBackgroundDownloadCoordinator.refreshSystemSessionIdle()
        if (systemTempCleanupAllowed()) {
            val referenced = iosResumeReferencedTempNames()
            for (dir in systemDownloadTempDirectories()) {
                NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
                    .mapNotNull { it as? String }
                    .filter { isSystemDownloadTempFile(it) && it !in referenced }
                    .forEach { name -> leftovers += LeftoverFile("$dir/$name", entrySize("$dir/$name")) }
            }
        }
        // Temp files listed as leftovers are inside Caches/tmp: count them once.
        val tempLeftoverBytes = leftovers.filter { isSystemDownloadTempFile(it.path.substringAfterLast('/')) }.sumOf { it.sizeBytes }
        return AppStorageUsage(
            totalBytes = containerBytes + outside,
            cacheBytes = (cacheBytes - tempLeftoverBytes).coerceAtLeast(0L),
            leftovers = leftovers,
        )
    }

    actual fun deleteLeftover(path: String): Boolean {
        val home = NSHomeDirectory().trimEnd('/')
        val downloadsPath = resolveDownloadsBaseDirectory().path.trimEnd('/')
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/')
        val defaultDir = downloadsDirectoryPath().trimEnd('/')
        val allowed = (isLooseSubtitleFile(name) && (parent == "$home/Documents" || parent == downloadsPath)) ||
            (parent == "$home/Library/Application Support/nuvio_download_resume" && name.endsWith(".resumedata")) ||
            (parent == defaultDir && defaultDir != downloadsPath && isNuvioDownloadFileName(name)) ||
            (isSystemDownloadTempFile(name) && parent in systemDownloadTempDirectories() &&
                systemTempCleanupAllowed() && name !in iosResumeReferencedTempNames())
        if (!allowed) return false
        if (parent == defaultDir || isSystemDownloadTempFile(name)) {
            releaseHeldDownloadFile(path)
            return removePathIfExists(path)
        }
        return resolveDownloadsBaseDirectory().withAccess { removePathIfExists(path) }
    }

    private fun systemTempCleanupAllowed(): Boolean =
        IosBackgroundDownloadCoordinator.systemSessionIdle && !IosBackgroundDownloadCoordinator.hasTrackedTransfers()

    /** Sum of every file under [path]. */
    private fun treeSize(path: String): Long {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return 0L
        val enumerator = manager.enumeratorAtPath(path) ?: return 0L
        var total = 0L
        while (true) {
            enumerator.nextObject() ?: break
            val attrs = enumerator.fileAttributes ?: continue
            if (attrs[NSFileType] == NSFileTypeDirectory) continue
            total += attrs[NSFileSize].asLong() ?: 0L
        }
        return total
    }

    actual fun listDownloadFiles(): List<DiskFile> = resolveDownloadsBaseDirectory().withAccess { path ->
        val top = NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)
            .orEmpty()
            .mapNotNull { it as? String }
            .filter { !it.startsWith(".") && it != DOWNLOAD_SUBTITLES_FOLDER }
            .map { name -> DiskFile(name, entrySize("$path/$name")) }
        // Subtitles live in Subtitles/<video file>/; listed as "<video file>.subtitles" so they
        // count with (and are cleaned up with) their download.
        val subtitles = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$path/$DOWNLOAD_SUBTITLES_FOLDER", null)
            .orEmpty()
            .mapNotNull { it as? String }
            .filter { !it.startsWith(".") }
            .map { name -> DiskFile("$name.subtitles", entrySize("$path/$DOWNLOAD_SUBTITLES_FOLDER/$name")) }
        top + subtitles
    }

    actual fun deleteDownloadFile(name: String): Boolean {
        if (name.isBlank() || name.contains('/') || name == "." || name == "..") return false
        return resolveDownloadsBaseDirectory().withAccess { path ->
            val organised = name.takeIf { it.endsWith(".subtitles") }?.removeSuffix(".subtitles")
                ?.let { "$path/$DOWNLOAD_SUBTITLES_FOLDER/$it" }
            val removedOrganised = organised?.let(::removePathIfExists) ?: true
            removePathIfExists("$path/$name") && removedOrganised
        }
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

/** Sizes exactly as iOS shows them (decimal units, ByteCountFormatter file style). */
internal actual fun platformByteCountString(bytes: Long): String? =
    NSByteCountFormatter.stringFromByteCount(bytes, NSByteCountFormatterCountStyleFile)
