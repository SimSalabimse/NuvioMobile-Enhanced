package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.runtime.Composable
import com.nuvio.app.features.diagnostics.DiagnosticsNavigation

@Composable
actual fun IosDiagnosticsSettingsRow(
    isTablet: Boolean,
) {
    SettingsNavigationRow(
        title = "Diagnostics",
        description = "On-device crash and error log",
        icon = Icons.Rounded.BugReport,
        isTablet = isTablet,
        onClick = { DiagnosticsNavigation.openDiagnosticsSettings() },
    )
}
