package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun IosDiagnosticsSettingsRow(
    isTablet: Boolean,
    modifier: Modifier
) {
    SettingsNavigationRow(
        title = "Diagnostics",
        description = "On-device crash and error log",
        icon = Icons.Rounded.BugReport,
        isTablet = isTablet,
        onClick = { 
            // This will be handled by Swift/UIKit navigation
            // The DiagnosticsBridge.openDiagnosticsSettings() will be called
            // through the native iOS navigation system
        },
        modifier = modifier
    )
}
