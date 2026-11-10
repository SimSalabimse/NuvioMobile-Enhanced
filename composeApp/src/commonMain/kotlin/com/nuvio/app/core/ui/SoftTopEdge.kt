package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Home hero. A short wash over the status area. */
internal const val HOME_SOFT_EDGE_PEAK = 0.42f

/** Details. The same wash, lighter, so the artwork still reads through the top. */
internal const val DETAIL_SOFT_EDGE_PEAK = 0.26f

/**
 * One soft top fade for the Home hero and the details header.
 * Reduce Transparency and Increase Contrast draw a solid band instead.
 * The node has no hit target.
 */
@Composable
internal fun SoftTopEdge(
    height: Dp,
    peakAlpha: Float,
    solid: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.background,
) {
    if (height <= 0.dp) return
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .testTag(tag)
            .semantics { hideFromAccessibility() }
            .drawBehind {
                if (solid) {
                    drawRect(color)
                } else {
                    drawRect(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to color.copy(alpha = peakAlpha),
                                1f to color.copy(alpha = 0f),
                            ),
                        ),
                    )
                }
            },
    )
}
