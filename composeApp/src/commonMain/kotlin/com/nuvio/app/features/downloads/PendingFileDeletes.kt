package com.nuvio.app.features.downloads

/**
 * Files whose delete could not be confirmed (still on disk, or a finished file found in none of
 * the folders, e.g. while a custom folder was not reachable). Kept across launches and retried at
 * launch and on Clean up leftovers; an entry that was never found is dropped after a few tries.
 */
internal object PendingFileDeletes {
    private const val KEY = "downloads_pending_file_deletes"
    private const val MAX_ATTEMPTS = 5

    internal data class Entry(val fileName: String, val localFileUri: String?, val attempts: Int)

    fun entries(): List<Entry> =
        DownloadsSettingsStorage.loadString(KEY).orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[1].isBlank()) return@mapNotNull null
                Entry(
                    fileName = parts[1],
                    localFileUri = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                    attempts = parts[0].toIntOrNull() ?: 0,
                )
            }
            .toList()

    private fun save(list: List<Entry>) {
        DownloadsSettingsStorage.saveString(
            KEY,
            list.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.attempts}\t${it.fileName}\t${it.localFileUri.orEmpty()}" },
        )
    }

    fun add(fileName: String, localFileUri: String?) {
        if (fileName.isBlank() || fileName.contains('\t') || fileName.contains('\n')) return
        val uri = localFileUri?.takeIf { !it.contains('\t') && !it.contains('\n') }
        save(entries().filterNot { it.fileName == fileName } + Entry(fileName, uri, 0))
    }

    /** [attempt] returns true when the entry is done (deleted, or in use again). */
    fun retry(attempt: (Entry) -> Boolean) {
        val current = entries()
        if (current.isEmpty()) return
        val left = current.mapNotNull { entry ->
            val done = runCatching { attempt(entry) }.getOrDefault(false)
            when {
                done -> null
                entry.attempts + 1 >= MAX_ATTEMPTS -> null
                else -> entry.copy(attempts = entry.attempts + 1)
            }
        }
        save(left)
    }
}
