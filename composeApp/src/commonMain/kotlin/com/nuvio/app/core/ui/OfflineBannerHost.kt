package com.nuvio.app.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.OfflineBannerHideFadeMs
import com.nuvio.app.core.network.OfflineBannerHoldMs
import com.nuvio.app.core.network.OfflineBannerShowMs
import com.nuvio.app.core.network.OfflineBannerSurface
import com.nuvio.app.core.network.isOfflineBannerCondition
import com.nuvio.app.core.network.offlineBannerMotionEnabled
import com.nuvio.app.core.network.offlineBannerView
import com.nuvio.app.core.network.onOfflineBannerTap
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.offline_banner_saved_data
import org.jetbrains.compose.resources.stringResource

private val OfflineBannerStripHeight = 48.dp

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
    var heldMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(offline) {
        if (!offline) {
            heldMs = 0L
            return@LaunchedEffect
        }
        delay(OfflineBannerHoldMs)
        heldMs = OfflineBannerHoldMs
    }
    val view = offlineBannerView(
        condition = condition,
        offlineHeldMs = heldMs,
        surface = surface,
        reduceMotion = reduceMotion,
    )
    val motion = offlineBannerMotionEnabled(surface, reduceMotion)
    val fraction by animateFloatAsState(
        targetValue = if (view.visible) 1f else 0f,
        animationSpec = if (motion) {
            tween(durationMillis = if (view.visible) OfflineBannerShowMs else OfflineBannerHideFadeMs)
        } else {
            snap()
        },
        label = "offlineBanner",
    )
    val showStrip = view.visible || fraction > 0f
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val label = stringResource(Res.string.offline_banner_saved_data)
    val tokens = MaterialTheme.nuvio
    Column(modifier) {
        if (showStrip) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(statusTop + OfflineBannerStripHeight * fraction)
                    .clipToBounds()
                    .background(tokens.colors.surfaceElevated)
                    .clickable { onOfflineBannerTap(onRecheck) }
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .graphicsLayer { alpha = fraction },
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (showStrip) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }
    }
}
