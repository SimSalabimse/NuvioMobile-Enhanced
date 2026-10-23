package com.nuvio.app.core.network

import com.nuvio.app.features.settings.PerformanceSettingsRepository
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP request deduplication and short-lived caching layer.
 * Item 5: Shared HTTP client + request dedupe + short caches.
 * 
 * CRITICAL: TheIntroDB submit/preview endpoints MUST always stay fresh (never cached).
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance.
 */
object HttpRequestCache {
    
    private data class CacheEntry(
        val response: HttpResponse,
        val timestamp: Long,
    )
    
    private data class PendingRequest(
        val mutex: Mutex,
        var response: HttpResponse? = null,
        var error: Throwable? = null,
    )
    
    private val cache = mutableMapOf<String, CacheEntry>()
    private val pending = mutableMapOf<String, PendingRequest>()
    private val cacheLock = Mutex()
    
    /**
     * Check if a URL should never be cached (TheIntroDB endpoints).
     */
    private fun isAlwaysFresh(url: String): Boolean {
        // CRITICAL: TheIntroDB submit and preview must always stay fresh
        return url.contains("theintrodb") || 
               url.contains("/submit") ||
               url.contains("/preview")
    }
    
    /**
     * Get cache key for a request.
     */
    private fun cacheKey(url: String, method: String): String = "$method:$url"
    
    /**
     * Execute request with deduplication and caching.
     */
    suspend fun <T> execute(
        url: String,
        method: String = "GET",
        block: suspend () -> HttpResponse,
    ): HttpResponse {
        PerformanceSettingsRepository.ensureLoaded()
        val dedupeEnabled = PerformanceSettingsRepository.requestDedupeEnabled.value
        val cacheEnabled = PerformanceSettingsRepository.shortHttpCachesEnabled.value
        val ttlSeconds = PerformanceSettingsRepository.httpCacheTTLSeconds.value
        
        // Skip cache/dedupe for always-fresh endpoints
        if (isAlwaysFresh(url)) {
            return block()
        }
        
        val key = cacheKey(url, method)
        
        // Check cache first (only for GET requests)
        if (cacheEnabled && method == "GET") {
            cacheLock.withLock {
                cache[key]?.let { entry ->
                    val age = (System.currentTimeMillis() - entry.timestamp) / 1000
                    if (age < ttlSeconds) {
                        return entry.response
                    } else {
                        cache.remove(key)
                    }
                }
            }
        }
        
        // Request deduplication for concurrent identical requests
        if (dedupeEnabled) {
            val pendingRequest = cacheLock.withLock {
                pending.getOrPut(key) { PendingRequest(Mutex(locked = true)) }
            }
            
            // If this is the first request, execute it
            if (pendingRequest.mutex.isLocked && pendingRequest.response == null && pendingRequest.error == null) {
                try {
                    val response = block()
                    cacheLock.withLock {
                        pendingRequest.response = response
                        // Cache successful GET responses
                        if (cacheEnabled && method == "GET") {
                            cache[key] = CacheEntry(response, System.currentTimeMillis())
                        }
                        pending.remove(key)
                    }
                    pendingRequest.mutex.unlock()
                    return response
                } catch (e: Throwable) {
                    cacheLock.withLock {
                        pendingRequest.error = e
                        pending.remove(key)
                    }
                    pendingRequest.mutex.unlock()
                    throw e
                }
            } else {
                // Wait for the ongoing request to complete
                pendingRequest.mutex.withLock {
                    pendingRequest.response?.let { return it }
                    pendingRequest.error?.let { throw it }
                }
            }
        }
        
        // No deduplication, execute normally
        val response = block()
        if (cacheEnabled && method == "GET") {
            cacheLock.withLock {
                cache[key] = CacheEntry(response, System.currentTimeMillis())
            }
        }
        return response
    }
    
    /**
     * Clear all cached responses.
     */
    fun clearCache() {
        cache.clear()
    }
    
    /**
     * Clear expired cache entries.
     */
    fun cleanupExpired() {
        PerformanceSettingsRepository.ensureLoaded()
        val ttlSeconds = PerformanceSettingsRepository.httpCacheTTLSeconds.value
        val now = System.currentTimeMillis()
        
        cache.entries.removeIf { (_, entry) ->
            val age = (now - entry.timestamp) / 1000
            age >= ttlSeconds
        }
    }
}
