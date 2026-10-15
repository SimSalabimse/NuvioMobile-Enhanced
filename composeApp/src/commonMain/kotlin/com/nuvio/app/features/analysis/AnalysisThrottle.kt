package com.nuvio.app.features.analysis

/** 0 full speed, 1 small slices with sleeps (playback on), 2 hold (the player is struggling). */
internal expect fun setAnalysisThrottleLevel(level: Int)
