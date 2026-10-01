package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor

/** Catalog and grid posters opt out of the loader crossfade so rows do not fade while scrolling. */
internal fun posterImageRequest(
    context: PlatformContext,
    data: Any?,
    fallbackUrl: String? = null,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(data)
        .crossfade(false)
    val fallback = fallbackUrl?.takeIf { it.isNotBlank() && it != data }
    if (fallback != null) {
        builder.memoryCacheKeyExtras(
            mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallback),
        )
    }
    return builder.build()
}
