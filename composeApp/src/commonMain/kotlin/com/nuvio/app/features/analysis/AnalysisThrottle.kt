package com.nuvio.app.features.analysis

/**
 * 0 full speed. 1, 2, and 3 pause the analyser until the level returns to 0:
 * playback, a struggling player, and heat or Low Power Mode.
 */
internal expect fun setAnalysisThrottleLevel(level: Int)

/** True while a player session is open, including while it is paused. iOS reads this for queue QoS. */
internal expect fun setAnalysisPlayerSessionOpen(open: Boolean)

/** Download id of the file being analysed, or empty. The analyser log reads this. No paths or URLs. */
internal expect fun setAnalysisActiveJob(jobId: String)

/** True while any download transfer is active. The analyser stays at utility QoS. */
internal expect fun setAnalysisTransferActive(active: Boolean)
