package com.nuvio.app.features.downloads

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

@OptIn(ExperimentalForeignApi::class)
internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val videoPath = requireNotNull(NSURL(string = localVideoUri).path)

    /** Before 0.6.1.1 (178): "<video>.subtitles" next to the video. */
    private val legacyDirectory = "$videoPath.subtitles"

    /** Per-download folder: "<downloads folder>/Subtitles/<video file name>/". */
    private val directory = run {
        val parent = videoPath.substringBeforeLast('/', "")
        val name = videoPath.substringAfterLast('/')
        val organised = "$parent/$DOWNLOAD_SUBTITLES_FOLDER/$name"
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(organised) && manager.fileExistsAtPath(legacyDirectory)) {
            manager.createDirectoryAtPath("$parent/$DOWNLOAD_SUBTITLES_FOLDER", true, null, null)
            if (!manager.moveItemAtPath(legacyDirectory, organised, null)) return@run legacyDirectory
        }
        organised
    }

    actual fun read(fileName: String): String? =
        NSString.stringWithContentsOfFile(path(fileName), NSUTF8StringEncoding, null)

    actual fun write(fileName: String, text: String) {
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        check(NSString.create(string = text).writeToFile(path(fileName), true, NSUTF8StringEncoding, null))
    }

    actual fun localFileUri(fileName: String): String? =
        path(fileName).takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            ?.let { NSURL.fileURLWithPath(it).absoluteString }

    actual fun remove() {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
        NSFileManager.defaultManager.removeItemAtPath(legacyDirectory, null)
    }

    private fun path(fileName: String): String {
        require(fileName.isNotBlank() && '/' !in fileName && fileName != "." && fileName != "..")
        return "$directory/$fileName"
    }
}

internal const val DOWNLOAD_SUBTITLES_FOLDER = "Subtitles"
