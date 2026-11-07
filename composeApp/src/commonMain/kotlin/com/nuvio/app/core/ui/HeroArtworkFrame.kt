package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
 * A landscape frame keeps the cinematic crop for wide art: horizontal focus and
 * the small extra scale. Tall art in that frame stays fitted.
 *
 * A portrait frame does not letterbox the picture inside the tall slot.
 * Wide art is a full-bleed 16:9 band (crop, no extra scale, no scroll zoom).
 * Tall art fits a 2:3 column. An unknown size uses that band at 1:1 and does
 * not apply the landscape extra scale.
 */
internal enum class HeroArtworkFit {
    CropLandscape,
    WideBand,
    PosterColumn,
}

internal data class HeroArtworkDecision(
    val fit: HeroArtworkFit,
    val extraScale: Float,
    val scrollScaleEnabled: Boolean,
    val horizontalFocus: Boolean,
    /** 1 fills the hero width. A poster column is narrower so the picture is not a tiny stamp. */
    val widthFraction: Float = 1f,
)

internal const val DETAIL_LANDSCAPE_EXTRA_SCALE = 1.08f
internal const val HOME_LANDSCAPE_EXTRA_SCALE = 1.14f

/** True when the hero slot is taller than it is wide. A square slot is not portrait. */
internal fun portraitHeroFrame(frameWidth: Float, frameHeight: Float): Boolean =
    frameWidth > 0f && frameHeight > frameWidth

internal const val PORTRAIT_WIDE_BAND_ASPECT = 16f / 9f
internal const val PORTRAIT_POSTER_ASPECT = 2f / 3f
internal const val PORTRAIT_POSTER_WIDTH_FRACTION = 0.62f

/**
 * Height of the portrait artwork itself. Wide and unknown art are a 16:9 band.
 * Tall art is a 2:3 column at [PORTRAIT_POSTER_WIDTH_FRACTION] of the frame.
 * The title sits under this height. Nothing is reserved below the picture.
 */
internal fun portraitArtworkSlotHeight(frameWidth: Dp, tallArt: Boolean): Dp {
    val width = frameWidth.value
    val height = if (tallArt) {
        width * PORTRAIT_POSTER_WIDTH_FRACTION / PORTRAIT_POSTER_ASPECT
    } else {
        width / PORTRAIT_WIDE_BAND_ASPECT
    }
    return height.dp
}

internal fun heroArtworkDecision(
    imageWidth: Int,
    imageHeight: Int,
    frameWidth: Float,
    frameHeight: Float,
    landscapeExtraScale: Float,
    portrait: Boolean = portraitHeroFrame(frameWidth, frameHeight),
): HeroArtworkDecision {
    val unknown = HeroArtworkDecision(
        fit = if (portrait) HeroArtworkFit.WideBand else HeroArtworkFit.CropLandscape,
        extraScale = 1f,
        scrollScaleEnabled = false,
        horizontalFocus = portrait,
    )
    if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0f || frameHeight <= 0f) return unknown
    val tallArt = imageHeight > imageWidth
    if (tallArt) {
        return HeroArtworkDecision(
            fit = HeroArtworkFit.PosterColumn,
            extraScale = 1f,
            scrollScaleEnabled = false,
            horizontalFocus = false,
            widthFraction = if (portrait) PORTRAIT_POSTER_WIDTH_FRACTION else 1f,
        )
    }
    if (portrait) {
        return HeroArtworkDecision(
            fit = HeroArtworkFit.WideBand,
            extraScale = 1f,
            scrollScaleEnabled = false,
            horizontalFocus = true,
        )
    }
    return HeroArtworkDecision(
        fit = HeroArtworkFit.CropLandscape,
        extraScale = landscapeExtraScale,
        scrollScaleEnabled = true,
        horizontalFocus = true,
    )
}

/**
 * Height of the image as a fraction of the slot. 1 fills a slot that is already the
 * artwork height. [portrait] is the phone orientation, not the slot's own aspect.
 */
internal fun heroImageHeightFraction(
    decision: HeroArtworkDecision,
    frameWidth: Float,
    frameHeight: Float,
    portrait: Boolean = portraitHeroFrame(frameWidth, frameHeight),
): Float {
    if (frameWidth <= 0f || frameHeight <= 0f) return 1f
    if (!portrait) return 1f
    val heightOverWidth = when (decision.fit) {
        HeroArtworkFit.WideBand -> 1f / PORTRAIT_WIDE_BAND_ASPECT
        HeroArtworkFit.PosterColumn -> decision.widthFraction / PORTRAIT_POSTER_ASPECT
        HeroArtworkFit.CropLandscape -> return 1f
    }
    return (frameWidth * heightOverWidth / frameHeight).coerceIn(0.34f, 1f)
}
