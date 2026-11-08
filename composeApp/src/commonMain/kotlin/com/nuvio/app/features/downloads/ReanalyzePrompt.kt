package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.analysis.MediaAnalysisScheduler

/** What a "Re-analyze" action covers: one episode or movie, a season, or a whole show. */
internal data class ReanalyzeTarget(val label: String, val ids: List<String>)

internal const val REANALYZE_CONFIRMATION_TAG = "reanalyze-confirmation"

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
internal fun ReanalyzeModal(target: ReanalyzeTarget?, onDismiss: () -> Unit) {
    target ?: return
    val items = DownloadsRepository.uiState.value.items
        .filter { it.id in target.ids && it.status == DownloadStatus.Completed }
    val count = items.size
    val tokens = MaterialTheme.nuvio
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
            modifier = Modifier.fillMaxSize(),
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
            Column(
                modifier = Modifier.padding(tokens.spacing.dialogPadding),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            ) {
                Text(
                    text = "Re-analyze ${target.label}?",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = if (count == 0) {
                        "Nothing finished downloading here yet."
                    } else {
                        "Runs intro, credits, recap, subtitle and loudness analysis again for " +
                            (if (count == 1) "this file" else "these $count files") +
                            ". Your own skip marks are kept."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                DialogButtons {
                    if (count > 0) {
                        DialogButton(text = "Cancel", onClick = onDismiss)
                    }
                    DialogButton(
                        text = if (count == 0) "OK" else "Re-analyze",
                        onClick = {
                            if (items.isNotEmpty()) MediaAnalysisScheduler.reanalyse(items)
                            onDismiss()
                        },
                        style = DialogButtonStyle.Primary,
                    )
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
