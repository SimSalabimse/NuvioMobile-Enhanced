package com.nuvio.app.features.analysis

/**
 * Phone heat and Low Power Mode. iOS reads ProcessInfo and listens for changes.
 * Other platforms report a cool phone, so the gate stays on playback alone.
 */
internal expect object AnalysisPower {
    fun snapshot(): AnalysisPowerSnapshot

    fun startObserving(onChange: () -> Unit)
}
