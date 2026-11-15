package com.nuvio.app.features.analysis

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.thermalState

internal actual object AnalysisPower {
    private var thermalObserver: Any? = null
    private var powerObserver: Any? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    actual fun snapshot(): AnalysisPowerSnapshot {
        val thermal = when (NSProcessInfo.processInfo.thermalState) {
            NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> AnalysisThermal.Fair
            NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> AnalysisThermal.Serious
            NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> AnalysisThermal.Critical
            else -> AnalysisThermal.Nominal
        }
        return AnalysisPowerSnapshot(
            thermal = thermal,
            lowPowerMode = NSProcessInfo.processInfo.lowPowerModeEnabled,
        )
    }

    actual fun startObserving(onChange: () -> Unit) {
        if (thermalObserver != null) return
        val center = NSNotificationCenter.defaultCenter
        val deliver: (NSNotification?) -> Unit = {
            mainScope.launch { onChange() }
        }
        thermalObserver = center.addObserverForName(
            name = NSProcessInfoThermalStateDidChangeNotification,
            `object` = null,
            queue = null,
            usingBlock = deliver,
        )
        powerObserver = center.addObserverForName(
            name = NSProcessInfoPowerStateDidChangeNotification,
            `object` = null,
            queue = null,
            usingBlock = deliver,
        )
    }
}
