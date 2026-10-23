package com.nuvio.app.core.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberAppBinarySizeBytes(): Long? = remember {
    val path = NSBundle.mainBundle.executablePath ?: return@remember null
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return@remember null
    (attributes[NSFileSize] as? NSNumber)?.longLongValue?.takeIf { it > 0L }
}
