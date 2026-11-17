package com.nuvio.app.features.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** iOS hosts a SwiftUI list. Android draws the Compose list. */
internal expect val seasonOverviewNativeList: Boolean

@Composable
internal expect fun SeasonOverviewNativeListHost(
    payload: String,
    onOpen: (String) -> Unit,
    onWatchPrivately: (String) -> Unit,
    onInclude: (String) -> Unit,
    onSelecting: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
)
