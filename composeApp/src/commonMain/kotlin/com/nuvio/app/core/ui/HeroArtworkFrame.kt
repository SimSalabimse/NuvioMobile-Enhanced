package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Stable
internal class HeroArtworkSizeState {
    var width by mutableIntStateOf(0)
        private set
    var height by mutableIntStateOf(0)
        private set

    fun onImageLoaded(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        this.width = width
        this.height = height
    }
}

@Composable
internal fun rememberHeroArtworkSize(url: String?): HeroArtworkSizeState =
    remember(url) { HeroArtworkSizeState() }

/**
 * How a hero draws one loaded image.
 *
 * Landscape art (wider than it is tall) keeps a width-filling crop, horizontal
 * focus, and the small extra scale the cinematic header already uses.
 * Portrait art is fit whole, at 1:1, with no extra scale and no scroll zoom.
 * An unknown size is fit whole at 1:1 until the image reports its size.
 */
internal enum class HeroArtworkFit {
    CropLandscape,
    FitPortrait,
}

internal data class HeroArtworkDecision(
    val fit: HeroArtworkFit,
    val extraScale: Float,
    val scrollScaleEnabled: Boolean,
    val horizontalFocus: Boolean,
)

internal const val DETAIL_LANDSCAPE_EXTRA_SCALE = 1.08f
internal const val HOME_LANDSCAPE_EXTRA_SCALE = 1.14f

internal fun heroArtworkDecision(
    imageWidth: Int,
    imageHeight: Int,
    frameWidth: Float,
    frameHeight: Float,
    landscapeExtraScale: Float,
): HeroArtworkDecision {
    if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0f || frameHeight <= 0f) {
        return HeroArtworkDecision(
            fit = HeroArtworkFit.FitPortrait,
            extraScale = 1f,
            scrollScaleEnabled = false,
            horizontalFocus = false,
        )
    }
    val portrait = imageHeight > imageWidth
    return if (portrait) {
        HeroArtworkDecision(
            fit = HeroArtworkFit.FitPortrait,
            extraScale = 1f,
            scrollScaleEnabled = false,
            horizontalFocus = false,
        )
    } else {
        HeroArtworkDecision(
            fit = HeroArtworkFit.CropLandscape,
            extraScale = landscapeExtraScale,
            scrollScaleEnabled = true,
            horizontalFocus = true,
        )
    }
}
