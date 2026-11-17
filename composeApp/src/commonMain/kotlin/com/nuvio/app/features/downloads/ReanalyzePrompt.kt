package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DisplaySafeInsets
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.platformPhysicalSafeInsets
import com.nuvio.app.features.analysis.MediaAnalysisScheduler

/** What a "Re-analyze" action covers: one episode or movie, a season, or a whole show. */
internal data class ReanalyzeTarget(val label: String, val ids: List<String>)

internal const val REANALYZE_CONFIRMATION_TAG = "reanalyze-confirmation"
internal const val REANALYZE_BODY_TAG = "reanalyze-body"
internal const val REANALYZE_TITLE_TAG = "reanalyze-title"
internal const val REANALYZE_EXPLANATION_TAG = "reanalyze-explanation"
internal const val REANALYZE_CANCEL_TAG = "reanalyze-cancel"
internal const val REANALYZE_CONFIRM_TAG = "reanalyze-confirm"

/**
 * Where the confirmation card is drawn inside the viewport that measured it.
 * [viewportWidth] and [viewportHeight] are that viewport, in the same pixels as the card.
 * Centering in a wider, shorter container leaves the card past the trailing edge.
 */
internal data class ConfirmationFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    fun inside(viewportWidth: Int, viewportHeight: Int): Boolean =
        left >= 0 && top >= 0 && right <= viewportWidth && bottom <= viewportHeight
}

internal fun confirmationFrame(
    viewportWidth: Int,
    viewportHeight: Int,
    cardWidth: Int,
    cardHeight: Int,
): ConfirmationFrame {
    val width = cardWidth.coerceIn(0, viewportWidth.coerceAtLeast(0))
    val height = cardHeight.coerceIn(0, viewportHeight.coerceAtLeast(0))
    return ConfirmationFrame(
        left = ((viewportWidth - width) / 2).coerceAtLeast(0),
        top = ((viewportHeight - height) / 2).coerceAtLeast(0),
        width = width,
        height = height,
    )
}

/**
 * Confirms and queues on-device analysis again for just [target]. The user's own skip marks are
 * kept (the store's reset leaves them); progress shows in the usual analysis status.
 *
 * The card is placed in the layout that measured it, which is the same layout as the download list.
 * A platform dialog is centered in its own container. After the player has held the window in
 * landscape, that container can still be the landscape size while the list is portrait, so the
 * card's leading edge lands in the trailing half of the portrait screen and the rest is clipped.
 */
@Composable
internal fun ReanalyzeModal(
    target: ReanalyzeTarget?,
    onDismiss: () -> Unit,
    safeInsets: DisplaySafeInsets = platformPhysicalSafeInsets(),
) {
    target ?: return
    val items = DownloadsRepository.uiState.value.items
        .filter { it.id in target.ids && it.status == DownloadStatus.Completed }
    val count = items.size
    val tokens = MaterialTheme.nuvio
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    PlatformBackHandler(enabled = true, onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = safeInsets.left,
                    top = safeInsets.top,
                    end = safeInsets.right,
                    bottom = safeInsets.bottom,
                ),
            content = {
        Surface(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .widthIn(max = tokens.components.dialogMaxWidth)
                .testTag(REANALYZE_CONFIRMATION_TAG)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            contentColor = tokens.colors.textPrimary,
        ) {
            val title = "Re-analyze ${target.label}?"
            val explanation = if (count == 0) {
                "Nothing finished downloading here yet."
            } else {
                "Runs intro, credits, recap, subtitle and loudness analysis again for " +
                    (if (count == 1) "this file" else "these $count files") +
                    ". Your own skip marks are kept."
            }
            SubcomposeLayout { constraints ->
                val buttonPlaceables = subcompose(REANALYZE_CONFIRM_TAG) {
                    DialogButtons(
                        modifier = Modifier.padding(
                            start = tokens.spacing.dialogPadding,
                            end = tokens.spacing.dialogPadding,
                            bottom = tokens.spacing.dialogPadding,
                        ),
                    ) {
                        if (count > 0) {
                            DialogButton(
                                text = "Cancel",
                                onClick = onDismiss,
                                modifier = Modifier.testTag(REANALYZE_CANCEL_TAG),
                            )
                        }
                        DialogButton(
                            text = if (count == 0) "OK" else "Re-analyze",
                            onClick = {
                                if (items.isNotEmpty()) MediaAnalysisScheduler.reanalyse(items)
                                onDismiss()
                            },
                            modifier = Modifier.testTag(REANALYZE_CONFIRM_TAG),
                            style = DialogButtonStyle.Primary,
                        )
                    }
                }.map { measurable ->
                    measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                }
                val buttonsHeight = buttonPlaceables.sumOf { it.height }
                val bodyMax = (constraints.maxHeight - buttonsHeight).coerceAtLeast(0)
                val bodyMaxDp = with(density) { bodyMax.toDp() }
                val bodyPlaceables = subcompose(REANALYZE_BODY_TAG) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = bodyMaxDp)
                            .verticalScroll(scroll)
                            .padding(
                                start = tokens.spacing.dialogPadding,
                                top = tokens.spacing.dialogPadding,
                                end = tokens.spacing.dialogPadding,
                            )
                            .testTag(REANALYZE_BODY_TAG),
                        verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.testTag(REANALYZE_TITLE_TAG),
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.colors.textPrimary,
                            overflow = TextOverflow.Clip,
                        )
                        Text(
                            text = explanation,
                            modifier = Modifier.testTag(REANALYZE_EXPLANATION_TAG),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textSecondary,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }.map { measurable ->
                    measurable.measure(
                        Constraints(
                            maxWidth = constraints.maxWidth,
                            maxHeight = bodyMax,
                        ),
                    )
                }
                val bodyHeight = bodyPlaceables.sumOf { it.height }
                val width = maxOf(
                    constraints.maxWidth,
                    bodyPlaceables.maxOfOrNull { it.width } ?: 0,
                    buttonPlaceables.maxOfOrNull { it.width } ?: 0,
                ).coerceAtMost(constraints.maxWidth)
                val height = (bodyHeight + buttonsHeight).coerceAtMost(constraints.maxHeight)
                layout(width, height) {
                    var y = 0
                    bodyPlaceables.forEach { placeable ->
                        placeable.place(0, y)
                        y += placeable.height
                    }
                    buttonPlaceables.forEach { placeable ->
                        placeable.place(0, y)
                        y += placeable.height
                    }
                }
            }
        }
            },
        ) { measurables, constraints ->
            val viewportWidth = constraints.maxWidth.coerceAtLeast(0)
            val viewportHeight = constraints.maxHeight.coerceAtLeast(0)
            val card = measurables.first().measure(
                Constraints(maxWidth = viewportWidth, maxHeight = viewportHeight),
            )
            val frame = confirmationFrame(viewportWidth, viewportHeight, card.width, card.height)
            layout(viewportWidth, viewportHeight) {
                card.place(frame.left, frame.top)
            }
        }
    }
}
