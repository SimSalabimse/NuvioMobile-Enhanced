package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor

/** Poster rows stay crisp unless Settings → Appearance → Fade posters in is on. */
internal fun posterImageRequest(
    context: PlatformContext,
    data: Any?,
    fallbackUrl: String? = null,
    crossfade: Boolean = false,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(data)
        .crossfade(crossfade)
    val fallback = fallbackUrl?.takeIf { it.isNotBlank() && it != data }
    if (fallback != null) {
        builder.memoryCacheKeyExtras(
            mapOf(CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallback),
        )
    }
    return builder.build()
}
