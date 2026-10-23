package com.nuvio.app.core.cache

import com.nuvio.app.features.settings.PerformanceSettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LRU (Least Recently Used) cache with configurable size cap.
 * Item 6: Capped LRU caches for controlled memory usage.
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance.
 */
class LruCache<K, V>(
    private val maxSize: Int,
) {
    private val cache = LinkedHashMap<K, V>(maxSize, 0.75f, true)
    private val mutex = Mutex()
    
    suspend fun get(key: K): V? = mutex.withLock {
        if (!isEnabled()) return null
        cache[key]
    }
    
    suspend fun put(key: K, value: V) = mutex.withLock {
        if (!isEnabled()) return
        
        cache[key] = value
        
        // Evict oldest entry if over capacity
        if (cache.size > maxSize) {
            val oldest = cache.entries.first()
            cache.remove(oldest.key)
        }
    }
    
    suspend fun remove(key: K): V? = mutex.withLock {
        cache.remove(key)
    }
    
    suspend fun clear() = mutex.withLock {
        cache.clear()
    }
    
    suspend fun size(): Int = mutex.withLock {
        cache.size
    }
    
    suspend fun containsKey(key: K): Boolean = mutex.withLock {
        if (!isEnabled()) return false
        cache.containsKey(key)
    }
    
    private fun isEnabled(): Boolean {
        PerformanceSettingsRepository.ensureLoaded()
        return PerformanceSettingsRepository.cappedLruCachesEnabled.value
    }
    
    companion object {
        // Common cache size limits
        const val SMALL_CACHE_SIZE = 50
        const val MEDIUM_CACHE_SIZE = 100
        const val LARGE_CACHE_SIZE = 200
        
        /**
         * Create an LRU cache with size based on cache type.
         */
        fun <K, V> create(maxSize: Int = MEDIUM_CACHE_SIZE): LruCache<K, V> {
            return LruCache(maxSize)
        }
    }
}

/**
 * Simple in-memory LRU cache for metadata and thumbnails.
 */
object MetadataLruCache {
    private val metadataCache = LruCache.create<String, Any>(LruCache.LARGE_CACHE_SIZE)
    private val thumbnailCache = LruCache.create<String, ByteArray>(LruCache.MEDIUM_CACHE_SIZE)
    
    suspend fun getMetadata(key: String): Any? = metadataCache.get(key)
    suspend fun putMetadata(key: String, value: Any) = metadataCache.put(key, value)
    
    suspend fun getThumbnail(key: String): ByteArray? = thumbnailCache.get(key)
    suspend fun putThumbnail(key: String, value: ByteArray) = thumbnailCache.put(key, value)
    
    suspend fun clearAll() {
        metadataCache.clear()
        thumbnailCache.clear()
    }
}
