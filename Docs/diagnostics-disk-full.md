# Diagnostics file writes when the disk is full

The iOS diagnostics log is off unless Settings has "Save diagnostics log" on. While it is on, every saved line is appended to `diagnostics.jsonl`. That append used to call `FileHandle.seekToEndOfFile()` and `FileHandle.write(_:)`. Those Objective-C methods raise when the disk is full, and Swift cannot catch that exception, so the app crashed. The usual moment was a download-failure line written because storage was already full.

The project deployment target is iOS 16.1 (`IPHONEOS_DEPLOYMENT_TARGET` in `iosApp/iosApp.xcodeproj/project.pbxproj`). `FileHandle.seekToEnd()` and `FileHandle.write(contentsOf:)` are available from iOS 13.0 and 13.4, so this target uses them directly. No `FileHandle.synchronize` fallback is required.

## What the writer does

`DiagnosticsFileAppender` appends with the throwing methods. `DiagnosticsFileLogWriter` catches any error.

- After a failed write, file logging pauses for 30 seconds (`DiagnosticsFileWriteGate.failureBackoff`), then the next line tries again.
- Before a write, free space is read with `volumeAvailableCapacityForImportantUsage`. Under 50 MB (`50 * 1024 * 1024` bytes) the write is skipped. The skip is not a pause: the next line checks again, and a write is allowed at exactly 50 MB.
- If the capacity value cannot be read, the write is still attempted. The `do/catch` covers a full disk in that case.
- Lines are not queued in a second diagnostics buffer. There is no diagnostics line buffer. The separate in-app debug log (`InAppLogger`) is unchanged and is not paused. The latest analysis timing shown on the Diagnostics screen is still stored before the file append.

Rotation still runs after a line is actually written, and still keeps the newest half once the file is over about 4 MB.

## Crash marker

The marker key is still `NuvioDiagnosticsCrashMarker`. On launch, a set marker still logs "App did not terminate cleanly on previous launch (possible crash)" when diagnostics saving is on, then the marker is armed.

It stays armed while the app is in the foreground, including the inactive state during startup. It is cleared when the app enters the background or is about to terminate, so a normal background is not reported as a crash. Returning to the foreground arms the marker again and does not log a crash line. If the process is already in the background the first time the logger is created, the marker is left clear, but a marker left by the previous process is still logged.

The fixed 5 second clear is gone. The Diagnostics screen, its strings, and every existing log method and level are unchanged.

## Tests

`iosApp/DiagnosticsFileWriteTests.swift` is an XCTest for the injected writer and the marker decisions: a throwing writer does not escape, writes pause, they resume after the backoff, low space skips, and background or terminate clears the marker without logging a crash. There is no Swift test target in `iosApp.xcodeproj`, and this machine has no Swift compiler, so that file was not run.

The same decision table is mirrored by `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/diagnostics/DiagnosticsFileWriteGateMirrorTest.kt` and run with `:composeApp:testAndroidHostTest`. That mirror is not the iOS implementation. It does not prove the Swift file compiles or that a device survives a full disk.

Device check: Settings, turn Diagnostics on, play and download as usual. The Diagnostics screen still shows lines.
