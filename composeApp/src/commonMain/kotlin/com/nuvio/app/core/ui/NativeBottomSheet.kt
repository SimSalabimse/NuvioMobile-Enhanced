package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal expect fun NuvioNativeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    showDragHandle: Boolean,
    fullHeight: Boolean,
    liquidGlass: Boolean,
    fitSheet: FitSheetHeightHost?,
    content: @Composable ColumnScope.() -> Unit,
)

internal expect val usesNativeNuvioBottomSheet: Boolean

internal expect fun dismissNativeNuvioBottomSheet()

/**
 * Dismiss the sheet that is on screen.
 * [durationMs] 0 removes it immediately (no slide). A positive value slides it away in that many
 * milliseconds. Callers that are not the season sheet keep using [dismissNativeNuvioBottomSheet].
 */
internal expect suspend fun dismissPresentedNuvioBottomSheet(durationMs: Int)
