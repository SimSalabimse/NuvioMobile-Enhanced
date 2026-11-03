package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.nuvio.app.core.network.OfflineBannerSurface
import com.nuvio.app.core.network.isOfflineBannerCondition
import com.nuvio.app.core.network.offlineBannerFrame
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
    // Matches reduceOfflineBannerLatch: offline is visible on this frame; leaving it
    // waits OfflineBannerHoldMs before the fade. Reduce Motion snaps that fade.
    LaunchedEffect(offline, surface) {
        latch = reduceOfflineBannerLatch(latch, offline, surface, nowMs = 0L)
        if (surface == OfflineBannerSurface.Main && !offline && latch.visible) {
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
    val fraction by animateFloatAsState(
        targetValue = if (latch.visible) 1f else 0f,
        animationSpec = if (motion && !latch.visible) {
            tween(durationMillis = OfflineBannerHideFadeMs)
        } else {
            snap()
        },
        label = "offlineBanner",
    )
    val showStrip = latch.visible || fraction > 0f
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val frame = offlineBannerFrame(
        statusInsetDp = statusTop.value,
        statusInsetAlreadyApplied = false,
        fontScale = LocalDensity.current.fontScale,
    )
    val label = stringResource(Res.string.offline_banner_saved_data)
    val tokens = MaterialTheme.nuvio
    // Overlay. The strip fades in place and never pushes the list down.
    Box(modifier) {
        content()
        if (showStrip) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = frame.topInsetDp.dp)
                    .heightIn(min = frame.stripHeightDp.dp)
                    .graphicsLayer { alpha = fraction }
                    .background(tokens.colors.surfaceElevated)
                    .clickable { onOfflineBannerTap(onRecheck) }
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
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
