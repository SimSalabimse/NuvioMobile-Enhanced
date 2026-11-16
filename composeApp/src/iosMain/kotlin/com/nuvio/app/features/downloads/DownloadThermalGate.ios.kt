package com.nuvio.app.features.downloads

import com.nuvio.app.features.analysis.AnalysisThermal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.thermalState

internal actual object DownloadThermalGate {
    private var observer: Any? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    actual fun blocksNextFile(): Boolean = thermalBlocksNextFile(currentThermal())

    actual fun startObserving(onChange: () -> Unit) {
        if (observer != null) return
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = NSProcessInfoThermalStateDidChangeNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            mainScope.launch { onChange() }
        }
    }

    private fun currentThermal(): AnalysisThermal = when (NSProcessInfo.processInfo.thermalState) {
        NSProcessInfoThermalState.NSProcessInfoThermalStateFair -> AnalysisThermal.Fair
        NSProcessInfoThermalState.NSProcessInfoThermalStateSerious -> AnalysisThermal.Serious
        NSProcessInfoThermalState.NSProcessInfoThermalStateCritical -> AnalysisThermal.Critical
        else -> AnalysisThermal.Nominal
    }
}
