package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.features.analysis.MediaAnalysisScheduler

/** What a "Re-analyze" action covers: one episode or movie, a season, or a whole show. */
internal data class ReanalyzeTarget(val label: String, val ids: List<String>)

/**
 * Confirms and queues on-device analysis again for just [target]. The user's own skip marks are
 * kept (the store's reset leaves them); progress shows in the usual analysis status.
 */
@Composable
internal fun ReanalyzeModal(target: ReanalyzeTarget?, onDismiss: () -> Unit) {
    target ?: return
    val items = DownloadsRepository.uiState.value.items
        .filter { it.id in target.ids && it.status == DownloadStatus.Completed }
    val count = items.size
    NuvioStatusModal(
        title = "Re-analyze ${target.label}?",
        message = if (count == 0) {
            "Nothing finished downloading here yet."
        } else {
            "Runs intro, credits, recap, subtitle and loudness analysis again for " +
                (if (count == 1) "this file" else "these $count files") +
                ". Your own skip marks are kept."
        },
        isVisible = true,
        confirmText = if (count == 0) "OK" else "Re-analyze",
        dismissText = if (count == 0) null else "Cancel",
        onConfirm = {
            if (items.isNotEmpty()) MediaAnalysisScheduler.reanalyse(items)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
