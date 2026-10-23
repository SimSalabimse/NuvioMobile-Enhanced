package com.nuvio.app.features.settings

/**
 * Performance metrics tracking for measuring optimization impact.
 * Tracks launch time, time to playback, and memory usage.
 * 
 * Implemented by Simsalabim for Nuvio Mobile performance analysis.
 */
object PerformanceMetrics {
    private var appStartTime: Long = 0L
    private var appLaunchCompleteTime: Long = 0L
    private var playbackStartTime: Long = 0L
    
    fun recordAppStart() {
        appStartTime = System.currentTimeMillis()
    }
    
    fun recordAppLaunchComplete() {
        appLaunchCompleteTime = System.currentTimeMillis()
    }
    
    fun recordPlaybackStart() {
        playbackStartTime = System.currentTimeMillis()
    }
    
    fun getLaunchTimeMs(): Long {
        if (appStartTime == 0L || appLaunchCompleteTime == 0L) return 0L
        return appLaunchCompleteTime - appStartTime
    }
    
    fun getTimeToPlaybackMs(): Long {
        if (appStartTime == 0L || playbackStartTime == 0L) return 0L
        return playbackStartTime - appStartTime
    }
    
    fun getCurrentMemoryMB(): Double {
        return getPlatformMemoryUsageMB()
    }
    
    fun getFormattedLaunchTime(): String {
        val ms = getLaunchTimeMs()
        if (ms == 0L) return "Not measured"
        return "${ms}ms"
    }
    
    fun getFormattedTimeToPlayback(): String {
        val ms = getTimeToPlaybackMs()
        if (ms == 0L) return "Not measured"
        return "${ms}ms"
    }
    
    fun getFormattedMemory(): String {
        val mb = getCurrentMemoryMB()
        return String.format("%.1f MB", mb)
    }
}

internal expect fun getPlatformMemoryUsageMB(): Double
