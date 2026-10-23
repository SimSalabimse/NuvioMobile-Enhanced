package com.nuvio.app.features.addons

import com.nuvio.app.core.perf.AddonResponseCache

internal val addonResponseCache = AddonResponseCache()

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
): String = addonResponseCache.fetch(url, forceRefresh) {
    if (forceRefresh) {
        httpGetTextWithHeaders(
            url = url,
            headers = mapOf("Cache-Control" to "no-cache"),
        )
    } else {
        httpGetText(url)
    }
}

/** Starts a background GET that a later [fetchAddonResponseText] for [url] reuses within [ttlMs]. */
internal fun prefetchAddonResponseText(url: String, ttlMs: Long): Boolean =
    addonResponseCache.prefetch(url, ttlMs) { httpGetText(url) }
