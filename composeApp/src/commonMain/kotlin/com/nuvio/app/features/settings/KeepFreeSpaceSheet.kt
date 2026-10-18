package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.downloads.AutopilotPlan
import com.nuvio.app.features.downloads.SmartDownloadSettings
import com.nuvio.app.features.downloads.StorageAutopilot
import com.nuvio.app.features.downloads.formatDownloadBytes

internal fun keepFreeLabel(gb: Int): String = if (gb == 0) "Off" else "Keep $gb GB free"

/**
 * "Keep free space" picker: choosing only previews what would go; nothing is deleted until Apply,
 * and never while the sheet is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeepFreeSpaceSheet(current: Int, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(current) }
    var plan by remember { mutableStateOf<AutopilotPlan?>(null) }
    LaunchedEffect(selected) {
        plan = null
        plan = StorageAutopilot.preview(selected)
    }
    NuvioModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = nuvioSafeBottomPadding(16.dp))) {
            Text(
                "Keep free space",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SmartDownloadSettings.KEEP_FREE_CHOICES_GB.forEach { gb ->
                NuvioBottomSheetActionRow(title = keepFreeLabel(gb), selected = gb == selected, onClick = { selected = gb })
            }
            Text(
                text = keepFreePreview(selected, plan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            NuvioPrimaryButton(
                text = "Apply",
                enabled = selected != current && plan != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                onClick = {
                    onDismiss()
                    SmartDownloadSettings.setKeepFreeGb(selected)
                },
            )
        }
    }
}

internal fun keepFreePreview(gb: Int, plan: AutopilotPlan?): String = when {
    gb == 0 -> "Watched downloads are never deleted for space."
    plan == null -> "Checking…"
    plan.ids.isEmpty() -> "Nothing would be removed now. Later, watched downloads go oldest first when free space drops below $gb GB."
    else -> "Would remove ${plan.ids.size} watched ${if (plan.ids.size == 1) "episode" else "episodes"}, ${formatDownloadBytes(plan.bytes)}, oldest first."
}
