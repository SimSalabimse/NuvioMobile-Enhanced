package com.nuvio.app.core.ui

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.nuvio.app.core.automation.AUTOMATION_SEASON_POSTER_URL
import com.nuvio.app.core.automation.AutomationStubs
import com.nuvio.app.core.automation.AutomationTestMode

/**
 * Stops Coil from opening a socket for a remote image while automation mode is on.
 * File and in-memory images still load. With the flag off this calls straight through.
 */
internal class AutomationImageBlock : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (AutomationTestMode.isActive &&
            chain.request.data.isRemoteImageRequest() &&
            !automationImageRequestAllowed(chain.request.data)
        ) {
            AutomationStubs.noteBlockedNetwork()
            return ErrorResult(
                image = null,
                request = chain.request,
                throwable = IllegalStateException("automation"),
            )
        }
        return chain.proceed()
    }
}

/**
 * The one remote image automation mode may load: the public TMDB file
 * [AUTOMATION_SEASON_POSTER_URL], including its w1280 / original renditions.
 */
internal fun automationImageRequestAllowed(data: Any?): Boolean {
    val text = data?.toString()?.substringBefore('?')?.substringBefore('#') ?: return false
    if (!text.startsWith("https://image.tmdb.org/t/p/")) return false
    return text.endsWith("/1E5baAaEse26fej7uHcjOgEE2t2.jpg")
}

private fun Any.isRemoteImageRequest(): Boolean {
    val text = toString()
    return text.startsWith("http://") || text.startsWith("https://")
}
