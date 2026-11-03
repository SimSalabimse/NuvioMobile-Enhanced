package com.nuvio.app.features.analysis

/** 0 full speed, 1 small slices with sleeps (playback on), 2 hold (the player is struggling). */
internal expect fun setAnalysisThrottleLevel(level: Int)

/** True while a player session is open, including while it is paused. iOS reads this for queue QoS. */
internal expect fun setAnalysisPlayerSessionOpen(open: Boolean)
