package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp

/** Blurs an unwatched episode's text (title, description) when a spoiler blur setting is on. */
internal fun Modifier.spoilerBlur(enabled: Boolean): Modifier = if (enabled) blur(6.dp) else this
