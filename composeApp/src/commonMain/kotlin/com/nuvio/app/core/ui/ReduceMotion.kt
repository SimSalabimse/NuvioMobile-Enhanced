package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
internal expect fun systemReduceMotionEnabled(): Boolean

/** iOS Increase Contrast. Android has no equivalent switch in this reader. */
@Composable
internal expect fun systemIncreaseContrastEnabled(): Boolean
