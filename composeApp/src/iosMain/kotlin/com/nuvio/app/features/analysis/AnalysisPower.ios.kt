package com.nuvio.app.features.analysis

import com.nuvio.app.nativebridge.NuvioAnalysisLowPowerMode
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSNotification
import platform.Foundation.NSThread
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.NSProcessInfoThermalState
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.thermalState
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState

@OptIn(ExperimentalForeignApi::class)
internal actual object AnalysisPower {
    private var thermalObserver: Any? = null
    private var powerObserver: Any? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val batteryLock = SynchronizedObject()
    private var lowBatteryCached = false

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
            lowBattery = lowBatteryNow(),
        )
    }

    /** UIDevice is main-thread only. A background caller gets the last main-thread reading. */
    private fun lowBatteryNow(): Boolean {
        if (NSThread.currentThread.isMainThread) {
            val value = readLowBattery()
            synchronized(batteryLock) { lowBatteryCached = value }
            return value
        }
        mainScope.launch {
            val value = readLowBattery()
            synchronized(batteryLock) { lowBatteryCached = value }
        }
        return synchronized(batteryLock) { lowBatteryCached }
    }

    private fun readLowBattery(): Boolean {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val charging = device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
        val level = device.batteryLevel
        return !charging && level >= 0f && level < 0.20f
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
