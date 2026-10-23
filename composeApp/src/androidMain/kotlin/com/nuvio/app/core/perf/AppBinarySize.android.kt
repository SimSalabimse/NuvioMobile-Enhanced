package com.nuvio.app.core.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
internal actual fun rememberAppBinarySizeBytes(): Long? {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val info = context.applicationInfo
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty())
                .sumOf { path -> File(path).length() }
        }.getOrNull()?.takeIf { it > 0L }
    }
}
