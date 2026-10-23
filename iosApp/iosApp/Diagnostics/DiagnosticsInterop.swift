// C ABI for Kotlin/Native (composeApp/src/nativeInterop/cinterop/DiagnosticsInterop.h).
//
// Release builds compile this app with whole-module optimization and do not
// emit a global `_OBJC_CLASS_$_DiagnosticsBridge`. DiagnosticsBridge.o still
// has the Swift methods and ObjC thunks; only that class symbol is missing.
// An Objective-C message to the class is a hard reference to the missing
// symbol, so the Release link fails. These wrappers call the Swift methods
// directly and keep the NuvioDiagnostics* names the cinterop header declares.
// CInt matches C `int` on the arm64 Kotlin bridge; do not widen that parameter.

private func nuvioDiagnosticsString(_ value: UnsafePointer<CChar>?) -> String {
    guard let value, let string = String(validatingUTF8: value) else {
        return ""
    }
    return string
}

@_cdecl("NuvioDiagnosticsOpenSettings")
public func NuvioDiagnosticsOpenSettings() {
    DiagnosticsBridge.openDiagnosticsSettings()
}

@_cdecl("NuvioDiagnosticsLogColdLaunchTime")
public func NuvioDiagnosticsLogColdLaunchTime(_ durationSeconds: Double) {
    DiagnosticsBridge.logColdLaunchTime(durationSeconds)
}

@_cdecl("NuvioDiagnosticsLogNetworkError")
public func NuvioDiagnosticsLogNetworkError(
    _ statusCode: CInt,
    _ url: UnsafePointer<CChar>?
) {
    DiagnosticsBridge.logNetworkError(
        statusCode: Int(statusCode),
        url: nuvioDiagnosticsString(url)
    )
}

@_cdecl("NuvioDiagnosticsLogError")
public func NuvioDiagnosticsLogError(_ message: UnsafePointer<CChar>?) {
    DiagnosticsBridge.logError(nuvioDiagnosticsString(message))
}

@_cdecl("NuvioDiagnosticsLogPlaybackStall")
public func NuvioDiagnosticsLogPlaybackStall(_ durationSeconds: Double) {
    DiagnosticsBridge.logPlaybackStall(durationSeconds)
}
