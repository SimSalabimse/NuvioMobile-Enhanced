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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * Confirms and queues on-device analysis again for just [target]. The user's own skip marks are
 * kept (the store's reset leaves them); progress shows in the usual analysis status.
 *
 * Drawn in the current layout, not in a platform dialog. The player locks the window to landscape,
 * and the dialog layer keeps that size after Downloads is portrait again. Centering in that layer
 * puts the alert past the right edge. This box uses the same bounds as the list.
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
        contentAlignment = Alignment.Center,
    ) {
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
    }
}
