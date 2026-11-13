package com.nuvio.app.core.perf

import coil3.PlatformContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.ByteString.Companion.encodeUtf8

internal data class PosterOfflineImageCacheHit(
    val path: Path?,
    val freshness: PosterImageCacheFreshness,
    val contentType: String?,
    val kind: PosterImageEntryKind = PosterImageEntryKind.Image,
)

internal interface PosterOfflineImageBlobStore {
    fun loadIndex(): List<PosterOfflineImageIndexRow>
    fun writeIndex(rows: Collection<PosterOfflineImageIndexRow>)
    fun blobSize(key: String): Long?
    fun blobPath(key: String): Path?
    fun writeBlob(key: String, bytes: ByteArray)
    fun deleteBlob(key: String)
    fun deleteUnknownBlobs(knownKeys: Set<String>)
}

internal class PosterOfflineImageDiskCache(
    private val blobs: PosterOfflineImageBlobStore,
    private val maxBytes: Long = PosterOfflineImageCachePolicy.MaxBytes,
    private val fileKey: (String) -> String = ::posterOfflineImageFileKey,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, PosterOfflineImageIndexRow>()
    private var loaded = false

    suspend fun open(cacheKey: String, nowMillis: Long): PosterOfflineImageCacheHit? = io {
        val key = fileKey(cacheKey)
        mutex.withLock {
            ensureLoaded()
            val row = entries.remove(key) ?: return@withLock null
            if (row.kind == PosterImageEntryKind.Miss) {
                val touched = row.copy(lastAccessMillis = nowMillis)
                entries[key] = touched
                return@withLock PosterOfflineImageCacheHit(
                    path = null,
                    freshness = PosterOfflineImageCachePolicy.freshness(touched.freshUntilMillis, nowMillis),
                    contentType = null,
                    kind = PosterImageEntryKind.Miss,
                )
            }
            val size = blobs.blobSize(key)
            if (size == null || size != row.sizeBytes || !PosterOfflineImageCachePolicy.isUsableCachedImage(size)) {
                blobs.deleteBlob(key)
                persistLocked()
                return@withLock null
            }
            val path = blobs.blobPath(key)
            if (path == null) {
                blobs.deleteBlob(key)
                persistLocked()
                return@withLock null
            }
            val touched = row.copy(lastAccessMillis = nowMillis)
            entries[key] = touched
            PosterOfflineImageCacheHit(
                path = path,
                freshness = PosterOfflineImageCachePolicy.freshness(touched.freshUntilMillis, nowMillis),
                contentType = touched.contentType,
                kind = PosterImageEntryKind.Image,
            )
        }
    }

    suspend fun contains(cacheKey: String): Boolean = io {
        val key = fileKey(cacheKey)
        mutex.withLock {
            ensureLoaded()
            val row = entries[key] ?: return@withLock false
            if (row.kind == PosterImageEntryKind.Miss) return@withLock true
            val size = blobs.blobSize(key)
            size != null && size == row.sizeBytes && PosterOfflineImageCachePolicy.isUsableCachedImage(size)
        }
    }

    suspend fun store(
        cacheKey: String,
        bytes: ByteArray,
        freshUntilMillis: Long,
        nowMillis: Long,
        contentType: String? = null,
    ): Boolean = io {
        val size = bytes.size.toLong()
        if (!PosterOfflineImageCachePolicy.isUsableCachedImage(size) || size > maxBytes) return@io false
        val key = fileKey(cacheKey)
        mutex.withLock {
            ensureLoaded()
            val evict = keysToEvict(protected = key, incoming = size)
            blobs.writeBlob(key, bytes)
            val written = blobs.blobSize(key)
            if (written == null || written != size) {
                blobs.deleteBlob(key)
                return@withLock false
            }
            evict.forEach { evicted ->
                entries.remove(evicted)
                blobs.deleteBlob(evicted)
            }
            entries.remove(key)
            entries[key] = PosterOfflineImageIndexRow(
                key = key,
                sizeBytes = size,
                freshUntilMillis = freshUntilMillis,
                lastAccessMillis = nowMillis,
                contentType = contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() },
            )
            persistLocked()
            true
        }
    }

    /** Records that this address had no picture. The bytes are not kept. */
    suspend fun storeMiss(cacheKey: String, freshUntilMillis: Long, nowMillis: Long): Boolean = io {
        val key = fileKey(cacheKey)
        mutex.withLock {
            ensureLoaded()
            blobs.deleteBlob(key)
            entries.remove(key)
            entries[key] = PosterOfflineImageIndexRow(
                key = key,
                sizeBytes = 0L,
                freshUntilMillis = freshUntilMillis,
                lastAccessMillis = nowMillis,
                contentType = null,
                kind = PosterImageEntryKind.Miss,
            )
            persistLocked()
            true
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val rows = blobs.loadIndex().sortedBy { it.lastAccessMillis }
        var dropped = false
        rows.forEach { row ->
            if (row.kind == PosterImageEntryKind.Miss) {
                blobs.deleteBlob(row.key)
                entries[row.key] = row
                return@forEach
            }
            val size = blobs.blobSize(row.key)
            if (size == null || size != row.sizeBytes || !PosterOfflineImageCachePolicy.isUsableCachedImage(size)) {
                blobs.deleteBlob(row.key)
                dropped = true
                return@forEach
            }
            entries[row.key] = row
        }
        blobs.deleteUnknownBlobs(entries.keys)
        if (dropped) persistLocked()
        loaded = true
    }

    private fun keysToEvict(protected: String, incoming: Long): List<String> {
        val elders = ArrayList<String>(entries.size)
        var used = 0L
        for ((key, row) in entries) {
            if (key == protected) continue
            elders += key
            used += row.sizeBytes
        }
        val evict = mutableListOf<String>()
        var index = 0
        while (used + incoming > maxBytes && index < elders.size) {
            val eldest = elders[index]
            used -= entries.getValue(eldest).sizeBytes
            evict += eldest
            index++
        }
        return evict
    }

    private fun persistLocked() {
        blobs.writeIndex(entries.values)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

internal fun posterOfflineImageFileKey(value: String): String =
    value.encodeUtf8().sha256().hex()

internal class OkioPosterOfflineImageBlobStore(
    directoryPath: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : PosterOfflineImageBlobStore {
    private val directory = directoryPath.toPath()

    override fun loadIndex(): List<PosterOfflineImageIndexRow> {
        val index = directory / IndexFileName
        if (!fileSystem.exists(index)) return emptyList()
        val text = runCatching { fileSystem.read(index) { readUtf8() } }.getOrNull() ?: return emptyList()
        return PosterOfflineImageCachePolicy.parseIndex(text)
    }

    override fun writeIndex(rows: Collection<PosterOfflineImageIndexRow>) {
        ensureDirectory()
        val temporary = directory / "$IndexFileName.tmp"
        fileSystem.write(temporary) { writeUtf8(PosterOfflineImageCachePolicy.indexText(rows)) }
        val target = directory / IndexFileName
        if (fileSystem.exists(target)) fileSystem.delete(target)
        fileSystem.atomicMove(temporary, target)
    }

    override fun blobSize(key: String): Long? {
        val path = blob(key) ?: return null
        if (!fileSystem.exists(path)) return null
        return runCatching { fileSystem.metadata(path).size }.getOrNull()
    }

    override fun blobPath(key: String): Path? {
        val path = blob(key) ?: return null
        return path.takeIf { fileSystem.exists(path) }
    }

    override fun writeBlob(key: String, bytes: ByteArray) {
        val safe = safeKey(key) ?: return
        ensureDirectory()
        val temporary = directory / "$safe.bin.tmp"
        val target = directory / "$safe.bin"
        fileSystem.write(temporary) { write(bytes) }
        if (fileSystem.exists(target)) fileSystem.delete(target)
        fileSystem.atomicMove(temporary, target)
    }

    override fun deleteBlob(key: String) {
        val path = blob(key) ?: return
        if (fileSystem.exists(path)) fileSystem.delete(path)
    }

    override fun deleteUnknownBlobs(knownKeys: Set<String>) {
        if (!fileSystem.exists(directory)) return
        val listed = runCatching { fileSystem.list(directory) }.getOrNull() ?: return
        listed.forEach { path ->
            val name = path.name
            when {
                name.endsWith(".tmp") -> fileSystem.delete(path)
                name.endsWith(".bin") -> {
                    val key = name.removeSuffix(".bin")
                    if (key !in knownKeys) fileSystem.delete(path)
                }
            }
        }
    }

    private fun blob(key: String): Path? {
        val safe = safeKey(key) ?: return null
        return directory / "$safe.bin"
    }

    private fun ensureDirectory() {
        fileSystem.createDirectories(directory)
    }

    private fun safeKey(key: String): String? =
        key.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }

    private companion object {
        const val IndexFileName = "index.v2"
    }
}

internal object PosterOfflineImageCaches {
    private val lock = SynchronizedObject()
    private var directory: String? = null
    private var cache: PosterOfflineImageDiskCache? = null

    fun forContext(context: PlatformContext): PosterOfflineImageDiskCache {
        val path = posterOfflineImageCacheDirectory(context)
        return synchronized(lock) {
            val existing = cache
            if (existing != null && directory == path) {
                existing
            } else {
                PosterOfflineImageDiskCache(OkioPosterOfflineImageBlobStore(path)).also {
                    cache = it
                    directory = path
                }
            }
        }
    }
}

internal expect fun posterOfflineImageCacheDirectory(context: PlatformContext): String
