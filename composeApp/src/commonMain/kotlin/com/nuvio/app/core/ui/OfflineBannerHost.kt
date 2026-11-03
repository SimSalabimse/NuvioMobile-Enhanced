package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.OfflineBannerHideFadeMs
import com.nuvio.app.core.network.OfflineBannerHoldMs
import com.nuvio.app.core.network.OfflineBannerLatch
import com.nuvio.app.core.network.OfflineBannerPhase
import com.nuvio.app.core.network.OfflineBannerSurface
import com.nuvio.app.core.network.isOfflineBannerCondition
import com.nuvio.app.core.network.offlineBannerChrome
import com.nuvio.app.core.network.offlineBannerMotionEnabled
import com.nuvio.app.core.network.onOfflineBannerTap
import com.nuvio.app.core.network.reduceOfflineBannerLatch
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.offline_banner_saved_data
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OfflineBannerHost(
    condition: NetworkCondition,
    surface: OfflineBannerSurface,
    reduceMotion: Boolean,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    val offline = isOfflineBannerCondition(condition)
    var latch by remember {
        mutableStateOf(reduceOfflineBannerLatch(OfflineBannerLatch(), offline, surface, nowMs = 0L))
    }
    // #77 set animated only while the banner was visible, and animateFloatAsState
    // retargeted in the same frame Home swapped trees, so the 140ms fade finished
    // before the next draw. This animatable stays composed until alpha reaches 0.
    // OfflineBannerHoldMs is 0: the fade starts on the online frame. Reduce Motion snaps.
    LaunchedEffect(offline, surface) {
        latch = reduceOfflineBannerLatch(latch, offline, surface, nowMs = 0L)
        if (surface == OfflineBannerSurface.Main && !offline && latch.visible && OfflineBannerHoldMs > 0L) {
            delay(OfflineBannerHoldMs)
            latch = reduceOfflineBannerLatch(
                latch,
                offline = false,
                surface = surface,
                nowMs = OfflineBannerHoldMs,
            )
        }
    }
    val motion = offlineBannerMotionEnabled(surface, reduceMotion)
    val bannerAlpha = remember { Animatable(if (latch.visible) 1f else 0f) }
    LaunchedEffect(latch.visible, motion) {
        val target = if (latch.visible) 1f else 0f
        val spec = if (motion && !latch.visible) {
            tween<Float>(durationMillis = OfflineBannerHideFadeMs)
        } else {
            snap()
        }
        bannerAlpha.animateTo(target, spec)
    }
    val fraction = bannerAlpha.value
    val phase = when {
        latch.visible -> OfflineBannerPhase.Offline
        fraction > 0f -> OfflineBannerPhase.Reconnecting
        else -> OfflineBannerPhase.Online
    }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Home and the details screen both draw this frame. The background includes
    // the status inset so the page and the poster cannot show through above it.
    val chrome = offlineBannerChrome(
        phase = phase,
        statusInsetDp = statusTop.value,
        fontScale = LocalDensity.current.fontScale,
    )
    val label = stringResource(Res.string.offline_banner_saved_data)
    val tokens = MaterialTheme.nuvio
    // Overlay. The frame stays put while the 250ms hide fade changes alpha.
    Box(modifier) {
        content()
        if (chrome.drawn) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(chrome.backgroundHeightDp.dp)
                    .graphicsLayer { alpha = fraction }
                    .background(tokens.colors.surfaceElevated.copy(alpha = 1f))
                    .clickable { onOfflineBannerTap(onRecheck) }
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = chrome.contentTopOffsetDp.dp)
                        .height(chrome.contentHeightDp.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
