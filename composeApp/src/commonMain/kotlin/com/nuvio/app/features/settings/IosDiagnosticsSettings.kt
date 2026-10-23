package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun IosDiagnosticsSettingsRow(
    isTablet: Boolean,
    modifier: Modifier = Modifier
)
