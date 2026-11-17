package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

internal actual val usesNativeNuvioBottomSheet: Boolean = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
internal actual fun NuvioNativeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    containerColor: Color,
    contentColor: Color,
    showDragHandle: Boolean,
    fullHeight: Boolean,
    liquidGlass: Boolean,
    fitSheet: FitSheetHeightHost?,
    content: @Composable ColumnScope.() -> Unit,
) = Unit

internal actual fun dismissNativeNuvioBottomSheet() = Unit

internal actual suspend fun dismissPresentedNuvioBottomSheet(durationMs: Int) = Unit
