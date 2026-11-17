package com.nuvio.app.features.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal actual fun seasonOverviewFontScale(): Float = changedSurfaceFontScale()

@Composable
internal actual fun changedSurfaceFontScale(): Float =
    LocalConfiguration.current.fontScale.coerceIn(0.8f, 3.2f)
