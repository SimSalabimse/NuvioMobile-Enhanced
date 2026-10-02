package com.nuvio.app.features.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.themePalette
import com.nuvio.app.core.ui.accentBrush
import kotlinx.coroutines.delay

private val ParentalGuideRowHeight = 18.dp
private val ParentalGuideRowGap = 2.dp

@Composable
internal fun ParentalGuideOverlay(
    warnings: List<ParentalWarning>,
    isVisible: Boolean,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 32.dp, top = 24.dp),
    topSafeInset: Boolean = false,
    endClearance: Dp = 0.dp,
) {
    val visibleWarnings = warnings.take(2)
    if (visibleWarnings.isEmpty()) return

    val count = visibleWarnings.size
    val totalLineHeight = (ParentalGuideRowHeight.value * count) +
        (ParentalGuideRowGap.value * (count - 1))
    val guideAccentBrush = MaterialTheme.themePalette.accentBrush()

    val containerAlpha = remember { Animatable(0f) }
    val lineHeightFraction = remember { Animatable(0f) }
    val itemAlphas = remember(count) { List(count) { Animatable(0f) } }
    var animating by remember { mutableStateOf(false) }

    LaunchedEffect(isVisible) {
        if (isVisible && !animating) {
            animating = true

            containerAlpha.animateTo(1f, tween(300))
            lineHeightFraction.animateTo(1f, tween(400, easing = FastOutSlowInEasing))

            for (i in 0 until count) {
                delay(80)
                itemAlphas[i].animateTo(1f, tween(200))
            }

            delay(5000)

            for (i in (count - 1) downTo 0) {
                delay(60)
                itemAlphas[i].animateTo(0f, tween(150))
            }

            delay(100)
            lineHeightFraction.animateTo(0f, tween(300, easing = FastOutSlowInEasing))

            delay(200)
            containerAlpha.animateTo(0f, tween(200))

            animating = false
            onAnimationComplete()
        } else if (!isVisible && animating) {
            for (i in (count - 1) downTo 0) {
                itemAlphas[i].snapTo(0f)
            }
            lineHeightFraction.snapTo(0f)
            containerAlpha.snapTo(0f)
            animating = false
            onAnimationComplete()
        }
    }

    if (containerAlpha.value <= 0f) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (topSafeInset) {
                    Modifier.windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                } else {
                    Modifier
                },
            )
            .padding(end = endClearance)
            .alpha(containerAlpha.value)
            .padding(contentPadding),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height((totalLineHeight * lineHeightFraction.value).dp)
                .clip(RoundedCornerShape(1.dp))
                .background(guideAccentBrush),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(ParentalGuideRowGap),
        ) {
            visibleWarnings.forEachIndexed { index, warning ->
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append(warning.label)
                        }
                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.4f))) {
                            append(" · ")
                        }
                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.5f))) {
                            append(warning.severity)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ParentalGuideRowHeight)
                        .alpha(itemAlphas.getOrNull(index)?.value ?: 0f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
        }
    }
}
