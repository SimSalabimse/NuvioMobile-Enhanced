package com.nuvio.app.core.perf

import coil3.PlatformContext
import platform.Foundation.NSHomeDirectory

@Suppress("UNUSED_PARAMETER")
internal actual fun posterOfflineImageCacheDirectory(context: PlatformContext): String =
    NSHomeDirectory() + "/Library/Caches/poster_offline_images"
