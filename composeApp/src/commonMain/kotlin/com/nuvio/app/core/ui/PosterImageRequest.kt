package com.nuvio.app.core.ui

import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor

/** Hero and backdrop decode cap. A phone slot is smaller than a 4K original. */
internal const val HERO_DECODE_MAX_EDGE_PX = 1280

internal fun heroDecodePixels(slotWidthPx: Int, slotHeightPx: Int): Pair<Int, Int> {
    val width = slotWidthPx.coerceAtLeast(1)
    val height = slotHeightPx.coerceAtLeast(1)
    val longEdge = maxOf(width, height)
    if (longEdge <= HERO_DECODE_MAX_EDGE_PX) return width to height
    val scale = HERO_DECODE_MAX_EDGE_PX.toFloat() / longEdge.toFloat()
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/** Decode a hero or backdrop at the slot size, not the original file. */
internal fun heroImageRequest(
    context: PlatformContext,
    data: Any?,
    slotWidthPx: Int,
    slotHeightPx: Int,
): ImageRequest {
    val (width, height) = heroDecodePixels(slotWidthPx, slotHeightPx)
    return ImageRequest.Builder(context)
        .data(data)
        .size(width, height)
        .precision(Precision.INEXACT)
        .build()
}

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
