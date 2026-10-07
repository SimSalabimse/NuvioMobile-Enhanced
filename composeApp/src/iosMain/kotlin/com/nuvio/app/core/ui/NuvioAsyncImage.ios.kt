package com.nuvio.app.core.ui

import com.nuvio.app.features.settings.ThemeSettingsRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext

@Composable
internal actual fun NuvioAsyncImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    animateIfPossible: Boolean,
    crossfade: Boolean,
) {
    val platformContext = LocalPlatformContext.current
    val posterFadeEnabled by ThemeSettingsRepository.posterFadeEnabled.collectAsState()
    val model = remember(imageUrl, platformContext, crossfade, posterFadeEnabled) {
        if (crossfade) {
            imageUrl
        } else {
            posterImageRequest(platformContext, imageUrl, crossfade = posterFadeEnabled)
        }
    }
    if (!animateIfPossible || !imageUrl.looksAnimated()) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val targetWidth = constraints.maxWidth
            .takeIf { it in 1..MaxAnimationTargetEdgePx }
            ?: DefaultAnimationTargetEdgePx
        val targetHeight = constraints.maxHeight
            .takeIf { it in 1..MaxAnimationTargetEdgePx }
            ?: DefaultAnimationTargetEdgePx

        val state = rememberAnimatedFrame(imageUrl, targetWidth, targetHeight)

        when {
            state.unavailable -> AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )

            state.bitmap != null -> Image(
                bitmap = state.bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        }
    }
}

private fun String.looksAnimated(): Boolean {
    val cleanUrl = substringBefore('?').substringBefore('#')
    return cleanUrl.endsWith(".gif", ignoreCase = true) ||
        cleanUrl.endsWith(".webp", ignoreCase = true)
}
