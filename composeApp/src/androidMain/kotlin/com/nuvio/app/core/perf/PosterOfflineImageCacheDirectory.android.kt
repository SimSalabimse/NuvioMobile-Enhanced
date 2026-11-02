package com.nuvio.app.core.perf

import coil3.PlatformContext

internal actual fun posterOfflineImageCacheDirectory(context: PlatformContext): String =
    context.cacheDir.resolve("poster_offline_images").absolutePath
