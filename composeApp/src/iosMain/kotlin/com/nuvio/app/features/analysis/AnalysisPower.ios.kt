package com.nuvio.app.features.analysis

import com.nuvio.app.nativebridge.NuvioAnalysisLowPowerMode
import kotlinx.cinterop.ExperimentalForeignApi
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

@OptIn(ExperimentalForeignApi::class)
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
            lowPowerMode = NuvioAnalysisLowPowerMode() != 0,
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
