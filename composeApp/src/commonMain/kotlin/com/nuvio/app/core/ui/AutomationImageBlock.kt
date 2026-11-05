package com.nuvio.app.core.ui

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.nuvio.app.core.automation.AutomationStubs
import com.nuvio.app.core.automation.AutomationTestMode

/**
 * Stops Coil from opening a socket for a remote image while automation mode is on.
 * File and in-memory images still load. With the flag off this calls straight through.
 */
internal class AutomationImageBlock : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (AutomationTestMode.isActive && chain.request.data.isRemoteImageRequest()) {
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

private fun Any.isRemoteImageRequest(): Boolean {
    val text = toString()
    return text.startsWith("http://") || text.startsWith("https://")
}
