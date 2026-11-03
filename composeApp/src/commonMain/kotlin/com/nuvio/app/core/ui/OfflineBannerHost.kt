package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.OfflineBannerScreen
import com.nuvio.app.core.network.OfflineBannerSurface
import com.nuvio.app.core.network.OfflineScene
import com.nuvio.app.core.network.isOfflineBannerCondition
import com.nuvio.app.core.network.offlineScene
import com.nuvio.app.core.network.offlineTransitionMotion
import com.nuvio.app.core.network.onOfflineBannerTap
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.offline_banner_saved_data
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OfflineBannerHost(
    condition: NetworkCondition,
    surface: OfflineBannerSurface,
    screen: OfflineBannerScreen,
    reduceMotion: Boolean,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    val showBanner = surface == OfflineBannerSurface.Main
    val offline = showBanner && isOfflineBannerCondition(condition)
    val motion = offlineTransitionMotion(reduceMotion || !showBanner)
    val progress = remember { Animatable(if (offline) 1f else 0f) }
    LaunchedEffect(offline, motion.durationMs, motion.snap) {
        progress.animateTo(if (offline) 1f else 0f, motion.finiteSpec())
    }
    BoxWithConstraints(modifier) {
        // Physical status bar, not the navigation safe area. The nav bar already
        // holds the back button; adding it again stacked a second row under it.
        val scene = offlineScene(
            offline = offline,
            progress = progress.value,
            screen = screen,
            statusInsetDp = platformPhysicalTopInset().value,
            reduceMotion = motion.snap,
            fontScale = LocalDensity.current.fontScale,
            viewportWidthDp = maxWidth.value,
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = scene.contentTopOffsetDp.dp)
                .graphicsLayer {
                    alpha = scene.contentAlpha
                    translationY = scene.contentSlideDp.dp.toPx()
                },
        ) {
            content()
        }
        if (scene.bannerDrawn) {
            OfflineBannerStrip(scene = scene, onRecheck = onRecheck)
        }
    }
}

@Composable
private fun OfflineBannerStrip(
    scene: OfflineScene,
    onRecheck: () -> Unit,
) {
    val label = stringResource(Res.string.offline_banner_saved_data)
    val color = MaterialTheme.nuvio.colors.surfaceElevated.copy(alpha = 1f)
    val textColor = MaterialTheme.nuvio.colors.textPrimary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(scene.bannerHeightDp.dp)
            .graphicsLayer {
                alpha = scene.bannerAlpha
                translationY = scene.bannerSlideDp.dp.toPx()
            },
    ) {
        if (scene.statusInsetDp > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(scene.statusInsetDp.dp)
                    .background(color),
            )
        }
        if (scene.label.width > 0f) {
            Box(
                modifier = Modifier
                    .padding(start = scene.label.left.dp, top = scene.label.top.dp)
                    .width(scene.label.width.dp)
                    .height(scene.label.height.dp)
                    .background(color)
                    .clickable(enabled = scene.bannerAlpha > 0.2f) { onOfflineBannerTap(onRecheck) }
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
