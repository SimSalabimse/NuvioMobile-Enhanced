package com.nuvio.app.features.settings

import platform.Foundation.NSProcessInfo

internal actual fun getPlatformMemoryUsageMB(): Double {
    val processInfo = NSProcessInfo.processInfo
    val bytes = processInfo.physicalMemory
    return bytes / (1024.0 * 1024.0)
}
