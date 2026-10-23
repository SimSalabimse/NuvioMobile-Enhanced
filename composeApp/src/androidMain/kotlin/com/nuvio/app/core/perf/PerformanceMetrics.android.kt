package com.nuvio.app.core.perf

import android.os.Debug
import android.os.Process
import android.os.SystemClock

internal actual fun processUptimeMs(): Long? =
    (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()).takeIf { it >= 0L }

internal actual fun currentProcessMemoryBytes(): Long? = runCatching {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    info.totalPss.toLong() * 1024L
}.getOrNull()?.takeIf { it > 0L }
