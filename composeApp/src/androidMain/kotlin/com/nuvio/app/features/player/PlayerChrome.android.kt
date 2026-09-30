package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun IosSystemMaterial(modifier: Modifier) = Unit

@Composable
internal actual fun playerReduceTransparencyEnabled(): Boolean = false

@Composable
internal actual fun playerReduceMotionEnabled(): Boolean = false
