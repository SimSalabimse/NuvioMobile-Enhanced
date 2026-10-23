package com.nuvio.app.core.perf

import androidx.compose.runtime.Composable

/** Size of the installed app binary (APKs on Android, the main executable on iOS). */
@Composable
internal expect fun rememberAppBinarySizeBytes(): Long?
