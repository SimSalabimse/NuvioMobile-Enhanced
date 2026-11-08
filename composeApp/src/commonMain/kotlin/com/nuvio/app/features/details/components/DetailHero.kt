package com.nuvio.app.features.details.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.app.features.details.accessibleHeroTitleStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.DETAIL_LANDSCAPE_EXTRA_SCALE
import com.nuvio.app.core.ui.HeroArtworkFit
import com.nuvio.app.core.ui.focusedAlignment
import com.nuvio.app.core.ui.heroArtworkDecision
import com.nuvio.app.core.ui.heroImageHeightFraction
import com.nuvio.app.core.ui.portraitArtworkSlotHeight
import com.nuvio.app.core.ui.portraitHeroFrame
import com.nuvio.app.core.ui.rememberBackdropFocusState
import com.nuvio.app.core.ui.rememberHeroArtworkSize
import com.nuvio.app.core.ui.featherArtwork
import com.nuvio.app.core.ui.detailChromeTopInset
import com.nuvio.app.core.ui.headingTopPaddingDp
import com.nuvio.app.core.ui.heroStretchHeight
import com.nuvio.app.core.ui.heroStretchZoom
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.player.MiniPlayerController
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.nuvio.app.core.ui.LocalVerticalBarPadding
import com.nuvio.app.core.ui.verticalBarWidth

@Composable
fun DetailHero(
    meta: MetaDetails,
    isTablet: Boolean = false,
    portraitPhone: Boolean = !isTablet,
    scrollOffset: () -> Int = { 0 },
    stretchPx: () -> Float = { 0f },
    contentMaxWidth: Dp = 560.dp,
    onHeightChanged: (Int) -> Unit = {},
    heroTrailerSourceUrl: String? = null,
    heroTrailerSourceAudioUrl: String? = null,
    heroTrailerReady: Boolean = false,
    heroTrailerPlayWhenReady: () -> Boolean = { false },
    heroTrailerMuted: Boolean = true,
    heroGradientColor: Color? = null,
    cinematicBackground: Boolean = false,
    onBackdropLoaded: (Painter, ImageBitmap?) -> Unit = { _, _ -> },
    onHeroTrailerMuteToggle: () -> Unit = {},
    onHeroTrailerReady: () -> Unit = {},
    onHeroTrailerEnded: () -> Unit = {},
    onHeroTrailerError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val heroHeight = detailHeroHeight(maxWidth - verticalBarWidth(), isTablet)
        val heroFrameWidth = maxWidth
        var loadedWidth by remember(meta.background, meta.poster) { mutableStateOf(0) }
        var loadedHeight by remember(meta.background, meta.poster) { mutableStateOf(0) }
        val tallArt = loadedWidth > 0 && loadedHeight > loadedWidth
        val artworkFrameHeight = if (portraitPhone) {
            portraitArtworkSlotHeight(heroFrameWidth, tallArt)
        } else {
            heroHeight
        }
        val portrait = portraitPhone || portraitHeroFrame(heroFrameWidth.value, artworkFrameHeight.value)
        val trailerAlpha by animateFloatAsState(
            targetValue = if (heroTrailerReady) 1f else 0f,
            animationSpec = tween(durationMillis = 300),
            label = "detail_hero_trailer_alpha",
        )
        val muteIconSize = if (isTablet) 20.dp else 22.dp
        val trailerAudioLabel = stringResource(
            if (heroTrailerMuted) Res.string.compose_player_muted else Res.string.compose_player_volume,
        )
        val chromeTop = detailChromeTopInset()
        val heroChromeTopPadding = chromeTop +
            8.dp +
            ((40.dp - muteIconSize) / 2)
        val bottomGradientColor = heroGradientColor ?: MaterialTheme.colorScheme.background
        var logoLoadError by remember(meta.id, meta.logo) {
            mutableStateOf(false)
        }
        val logoUrl = meta.logo?.takeIf { it.isNotBlank() }

        if (!portraitPhone) {
            val heroBaseHeightPx = with(LocalDensity.current) { heroHeight.roundToPx() }
            LaunchedEffect(heroBaseHeightPx) { onHeightChanged(heroBaseHeightPx) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { if (portraitPhone) onHeightChanged(it.height) },
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heroStretchHeight(artworkFrameHeight, stretchPx)
                .graphicsLayer {
                    clip = true
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (cinematicBackground) Modifier.featherArtwork() else Modifier),
                ) {
                    val imageUrl = meta.background ?: meta.poster
                    if (imageUrl != null) {
                        val backdropFocus = rememberBackdropFocusState(imageUrl)
                        val artworkSize = rememberHeroArtworkSize(imageUrl)
                        val artwork = heroArtworkDecision(
                            imageWidth = artworkSize.width,
                            imageHeight = artworkSize.height,
                            frameWidth = heroFrameWidth.value,
                            frameHeight = artworkFrameHeight.value,
                            landscapeExtraScale = DETAIL_LANDSCAPE_EXTRA_SCALE,
                            portrait = portrait,
                        )
                        val imageFraction = heroImageHeightFraction(
                            artwork,
                            heroFrameWidth.value,
                            artworkFrameHeight.value,
                            portrait = portrait,
                        )
                        val imageHeight = artworkFrameHeight * imageFraction
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = meta.name,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth(artwork.widthFraction)
                                .height(imageHeight)
                                .then(
                                    if (portrait) {
                                        Modifier.background(MaterialTheme.colorScheme.background)
                                    } else {
                                        Modifier
                                    },
                                )
                                .heroStretchZoom(stretchPx)
                                .graphicsLayer {
                                    translationY = if (portrait) 0f else scrollOffset() * 0.5f
                                    scaleX = artwork.extraScale
                                    scaleY = artwork.extraScale
                                },
                            alignment = if (artwork.horizontalFocus) {
                                backdropFocus.focusedAlignment(
                                    if (isTablet) Alignment.TopCenter else Alignment.Center,
                                )
                            } else {
                                Alignment.Center
                            },
                            contentScale = if (artwork.fit == HeroArtworkFit.PosterColumn) {
                                ContentScale.Fit
                            } else {
                                ContentScale.Crop
                            },
                            onSuccess = { state ->
                                val image = state.result.image
                                artworkSize.onImageLoaded(image.width, image.height)
                                loadedWidth = image.width
                                loadedHeight = image.height
                                backdropFocus.onImageLoaded(image)
                                onBackdropLoaded(
                                    state.painter,
                                    loadedBackdropImageBitmap(state.result),
                                )
                            },
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        )
                    }
                    if (heroTrailerSourceUrl != null) {
                        HeroTrailerPlayerSurface(
                            sourceUrl = heroTrailerSourceUrl,
                            sourceAudioUrl = heroTrailerSourceAudioUrl,
                            playWhenReady = heroTrailerPlayWhenReady() && !MiniPlayerController.isActive,
                            muted = heroTrailerMuted,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(
                                    artworkFrameHeight * heroImageHeightFraction(
                                        heroArtworkDecision(
                                            imageWidth = 16,
                                            imageHeight = 9,
                                            frameWidth = heroFrameWidth.value,
                                            frameHeight = artworkFrameHeight.value,
                                            landscapeExtraScale = DETAIL_LANDSCAPE_EXTRA_SCALE,
                                            portrait = portrait,
                                        ),
                                        heroFrameWidth.value,
                                        artworkFrameHeight.value,
                                        portrait = portrait,
                                    ),
                                )
                                .heroStretchZoom(stretchPx)
                                .graphicsLayer {
                                    val framePortrait = portrait
                                    alpha = trailerAlpha
                                    translationY = if (framePortrait) 0f else scrollOffset() * 0.5f
                                    val trailerScale = if (framePortrait) 1f else DETAIL_LANDSCAPE_EXTRA_SCALE
                                    scaleX = trailerScale
                                    scaleY = trailerScale
                                },
                            onReady = onHeroTrailerReady,
                            onEnded = onHeroTrailerEnded,
                            onError = onHeroTrailerError,
                        )
                    }
                }
                if (heroTrailerSourceUrl != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                enabled = heroTrailerReady,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = trailerAudioLabel,
                                onClick = onHeroTrailerMuteToggle,
                            ),
                    )
                    AnimatedContent(
                        targetState = heroTrailerMuted,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(LocalVerticalBarPadding.current)
                            .padding(
                                top = heroChromeTopPadding,
                                end = if (isTablet) 32.dp else 22.dp,
                            )
                            .graphicsLayer {
                                alpha = trailerAlpha * 0.72f
                            },
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(120)) + scaleIn(
                                initialScale = 0.82f,
                                animationSpec = tween(160),
                            )) togetherWith (fadeOut(animationSpec = tween(90)) + scaleOut(
                                targetScale = 1.12f,
                                animationSpec = tween(100),
                            ))
                        },
                        label = "detail_hero_trailer_mute_icon",
                    ) { muted ->
                        Icon(
                            imageVector = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = trailerAudioLabel,
                            tint = Color.White,
                            modifier = Modifier.size(muteIconSize),
                        )
                    }
                }

                if (!cinematicBackground && portraitPhone) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, bottomGradientColor),
                                ),
                            ),
                    )
                } else if (!cinematicBackground) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (isTablet) 360.dp else 320.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.00f to Color.Transparent,
                                        0.16f to bottomGradientColor.copy(alpha = 0.04f),
                                        0.32f to bottomGradientColor.copy(alpha = 0.14f),
                                        0.50f to bottomGradientColor.copy(alpha = 0.34f),
                                        0.68f to bottomGradientColor.copy(alpha = 0.62f),
                                        0.84f to bottomGradientColor.copy(alpha = 0.84f),
                                        1.00f to bottomGradientColor,
                                    ),
                                ),
                            ),
                    )
                }

                if (!portraitPhone) {
                    DetailHeroHeading(
                        meta = meta,
                        logoUrl = logoUrl,
                        logoLoadError = logoLoadError,
                        onLogoError = { logoLoadError = true },
                        isTablet = isTablet,
                        contentMaxWidth = contentMaxWidth,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }
        if (portraitPhone) {
            DetailHeroHeading(
                meta = meta,
                logoUrl = logoUrl,
                logoLoadError = logoLoadError,
                onLogoError = { logoLoadError = true },
                isTablet = false,
                contentMaxWidth = contentMaxWidth,
                modifier = Modifier.padding(
                    top = headingTopPaddingDp(artworkFrameHeight.value, chromeTop.value).dp,
                    bottom = 8.dp,
                ),
            )
        }
        }
    }
}

@Composable
private fun DetailHeroHeading(
    meta: MetaDetails,
    logoUrl: String?,
    logoLoadError: Boolean,
    onLogoError: () -> Unit,
    isTablet: Boolean,
    contentMaxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 32.dp else 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (logoUrl != null && !logoLoadError) {
            AsyncImage(
                model = logoUrl,
                contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                modifier = Modifier
                    .fillMaxWidth(if (isTablet) 0.56f else 0.72f)
                    .widthIn(max = contentMaxWidth)
                    .then(if (isTablet) Modifier.height(72.dp) else Modifier.aspectRatio(2.6f)),
                alignment = Alignment.Center,
                contentScale = ContentScale.Fit,
                onError = { onLogoError() },
            )
        } else {
            val titleStyle = if (isTablet) {
                MaterialTheme.typography.displaySmall
            } else {
                MaterialTheme.typography.displayLarge
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = meta.name,
                    modifier = Modifier.fillMaxWidth(),
                    style = accessibleHeroTitleStyle(
                        titleStyle,
                        maxWidth.value,
                        meta.name,
                        LocalDensity.current.fontScale,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (meta.genres.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = meta.genres.take(3).joinToString(" \u2022 "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun detailHeroHeight(maxWidth: Dp, isTablet: Boolean): Dp =
    if (!isTablet) {
        (maxWidth * 1.33f).coerceIn(420.dp, 760.dp)
    } else {
        (maxWidth * 0.42f).coerceIn(300.dp, 420.dp)
    }
