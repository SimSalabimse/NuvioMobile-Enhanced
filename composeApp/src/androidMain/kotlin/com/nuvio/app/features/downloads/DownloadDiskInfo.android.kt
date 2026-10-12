package com.nuvio.app.features.downloads

import android.content.Context
import android.os.StatFs
import java.io.File

internal actual object DownloadDiskInfo {
    private const val PAYLOAD_KEY_PREFIX = "downloads_payload_"
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun directory(): File? = appContext?.let { File(it.filesDir, "downloads") }

    actual fun volumeSpace(): VolumeSpace? {
        val dir = directory() ?: return null
        val target = if (dir.exists()) dir else dir.parentFile ?: return null
        return runCatching {
            val stat = StatFs(target.absolutePath)
            VolumeSpace(availableBytes = stat.availableBytes, totalBytes = stat.totalBytes)
        }.getOrNull()?.takeIf { it.totalBytes > 0L }
    }

    actual fun listDownloadFiles(): List<DiskFile> =
        directory()?.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") }
            .map { DiskFile(it.name, if (it.isDirectory) it.walkTopDown().filter(File::isFile).sumOf(File::length) else it.length()) }

    actual fun deleteDownloadFile(name: String): Boolean {
        if (name.isBlank() || name.contains('/') || name == "." || name == "..") return false
        val file = File(directory() ?: return false, name)
        if (!file.exists()) return true
        return if (file.isDirectory) file.deleteRecursively() else file.delete()
    }

    actual fun allProfilePayloads(): List<String> {
        val prefs = appContext?.getSharedPreferences("nuvio_downloads", Context.MODE_PRIVATE) ?: return emptyList()
        return prefs.all.filterKeys { it.startsWith(PAYLOAD_KEY_PREFIX) }.values.mapNotNull { it as? String }
    }
}
