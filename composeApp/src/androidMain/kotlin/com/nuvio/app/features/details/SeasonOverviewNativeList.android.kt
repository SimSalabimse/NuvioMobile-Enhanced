package com.nuvio.app.features.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal actual val seasonOverviewNativeList: Boolean = false

@Suppress("UNUSED_PARAMETER")
@Composable
internal actual fun SeasonOverviewNativeListHost(
    payload: String,
    onOpen: (String) -> Unit,
    onWatchPrivately: (String) -> Unit,
    onInclude: (String) -> Unit,
    onSelecting: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    // The Compose list is the Android sheet. These callbacks stay on that list.
}
