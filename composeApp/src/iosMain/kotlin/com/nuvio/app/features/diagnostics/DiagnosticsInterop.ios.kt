package com.nuvio.app.features.diagnostics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.ObjCObject
import kotlinx.cinterop.getOriginalKotlinClass
import platform.Foundation.NSClassFromString
import platform.darwin.NSObject
import platform.darwin.sel_registerName
import platform.objc.objc_msgSend

@OptIn(ExperimentalForeignApi::class)
actual object DiagnosticsLogger {
    private fun callStaticMethod(className: String, selector: String) {
        val clazz = NSClassFromString(className) ?: return
        objc_msgSend(clazz, sel_registerName(selector))
    }
    
    private fun callStaticMethodWithDouble(className: String, selector: String, value: Double) {
        val clazz = NSClassFromString(className) ?: return
        objc_msgSend(clazz, sel_registerName(selector), value)
    }
    
    private fun callStaticMethodWithIntAndString(className: String, selector: String, intValue: Int, stringValue: String) {
        val clazz = NSClassFromString(className) ?: return
        objc_msgSend(clazz, sel_registerName(selector), intValue.toLong(), stringValue)
    }
    
    private fun callStaticMethodWithString(className: String, selector: String, value: String) {
        val clazz = NSClassFromString(className) ?: return
        objc_msgSend(clazz, sel_registerName(selector), value)
    }
    
    actual fun logColdLaunchTime(durationSeconds: Double) {
        callStaticMethodWithDouble("DiagnosticsBridge", "logColdLaunchTime:", durationSeconds)
    }
    
    actual fun logNetworkError(statusCode: Int, url: String) {
        callStaticMethodWithIntAndString("DiagnosticsBridge", "logNetworkErrorWithStatusCode:url:", statusCode, url)
    }
    
    actual fun logError(message: String) {
        callStaticMethodWithString("DiagnosticsBridge", "logError:", message)
    }
    
    actual fun logPlaybackStall(durationSeconds: Double) {
        callStaticMethodWithDouble("DiagnosticsBridge", "logPlaybackStall:", durationSeconds)
    }
    
    actual fun isEnabled(): Boolean {
        return true
    }
}

object DiagnosticsNavigation {
    @OptIn(ExperimentalForeignApi::class)
    fun openDiagnosticsSettings() {
        val clazz = NSClassFromString("DiagnosticsBridge") ?: return
        objc_msgSend(clazz, sel_registerName("openDiagnosticsSettings"))
    }
}
