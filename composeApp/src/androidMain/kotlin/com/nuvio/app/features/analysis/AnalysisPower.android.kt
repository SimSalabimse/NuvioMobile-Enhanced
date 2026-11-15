package com.nuvio.app.features.analysis

internal actual object AnalysisPower {
    actual fun snapshot(): AnalysisPowerSnapshot =
        AnalysisPowerSnapshot(AnalysisThermal.Nominal, lowPowerMode = false)

    actual fun startObserving(onChange: () -> Unit) = Unit
}
