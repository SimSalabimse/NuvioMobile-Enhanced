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
        title = "Device error log",
        description = "Crashes and errors stored on this device. Not the session messages.",
        icon = Icons.Rounded.BugReport,
        isTablet = isTablet,
        onClick = { DiagnosticsNavigation.openDiagnosticsSettings() },
    )
}
