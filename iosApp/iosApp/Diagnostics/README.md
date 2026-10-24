# Nuvio iOS Diagnostics

On-device diagnostics logging for Nuvio Mobile iOS.

## Features

- **Local-only logging**: All logs stay on device, nothing is uploaded
- **Privacy-first**: Automatically redacts tokens, API keys, debrid links, full URLs, and account info
- **Automatic rotation**: Caps at ~4MB with automatic log rotation
- **Background logging**: All writes happen off the main thread
- **Crash detection**: Detects and logs unclean app terminations

## Files

All diagnostics files are in `iosApp/iosApp/Diagnostics/`:

- `DiagnosticsEntry.swift` - Log entry model and JSON Lines format
- `DiagnosticsRedactor.swift` - Redaction logic for sensitive data
- `DiagnosticsManager.swift` - Core logging manager with file operations
- `DiagnosticsSettingsView.swift` - Settings UI (toggle, export, clear)
- `DiagnosticsIntroSheet.swift` - One-time intro sheet shown on first launch
- `DiagnosticsBridge.swift` - Swift bridge for calling from Kotlin

Tests are in `iosApp/DiagnosticsTests/`:

- `DiagnosticsRedactorTests.swift` - Redaction test coverage
- `DiagnosticsManagerTests.swift` - Manager and file operation tests

Kotlin integration files (iOS-specific, not shared):

- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/diagnostics/DiagnosticsInterop.ios.kt`
- `composeApp/src/iosMain/kotlin/com/nuvio/app/features/settings/IosDiagnosticsSettings.ios.kt`
- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/diagnostics/DiagnosticsLogger.kt`
- `composeApp/src/androidMain/kotlin/com/nuvio/app/features/diagnostics/DiagnosticsLogger.android.kt`

## Adding to Xcode Project

1. Open `iosApp.xcodeproj` in Xcode
2. Right-click on `iosApp` group → Add Files to "iosApp"
3. Select all files in `iosApp/iosApp/Diagnostics/` folder
4. Make sure "Copy items if needed" is **unchecked** (files are already in place)
5. Make sure "iosApp" target is checked
6. Click Add

For tests:
1. Right-click on test target → Add Files
2. Select all files in `iosApp/DiagnosticsTests/`
3. Make sure test target is checked

## Integration Notes

The Diagnostics feature is iOS-only and kept in our own files (not upstream-shared paths). The integration is intentionally minimal:

1. **Intro Sheet**: Automatically shown via `ContentView.swift` - already wired up
2. **Settings Navigation**: The Diagnostics row appears in Settings > Advanced section on iOS only
3. **Navigation Wiring**: The Settings row calls `DiagnosticsBridge.openDiagnosticsSettings()` (through the `NuvioDiagnosticsOpenSettings` C wrapper), which presents `DiagnosticsSettingsView` modally from the top-most view controller. The app keeps one SwiftUI navigation stack per tab, so the bridge must not look for "the" navigation controller to push on: the first one in the hierarchy belongs to the Home tab, and a push there stays hidden until the user switches tabs.

## What Gets Logged

When enabled (default: ON):

- **Crashes**: Detected on next launch via crash marker
- **Errors**: Failed requests with HTTP status codes (host only, no full URLs)
- **Performance**: Cold launch time, tap-to-playback time
- **Playback**: Buffering/stall duration
- **Downloads**: Download failure events

Each entry includes:
- Timestamp (ISO8601)
- App version and build number
- iOS version
- Category
- Redacted message

## Privacy & Redaction

Never logged:
- Passwords or tokens
- API keys or bearer tokens
- Debrid download links
- Full URLs (only hostname is kept)
- Email addresses
- Account information

All messages are run through `DiagnosticsRedactor` before writing.

## Settings UI

**Settings > Advanced > Diagnostics**:

- Toggle: "Save diagnostics log" (ON by default)
- Explanation: "Stays on this phone. Nothing is uploaded."
- Buttons: Export (iOS share sheet), Clear
- Log size display
- Turning OFF immediately stops logging AND deletes the log file

## One-Time Intro

On first launch after installing this version:
- Sheet appears 1.5s after home screen loads (never blocks launch)
- Title: "Diagnostics log"
- Brief plain-English explanation
- Toggle (on by default)
- Done button saves choice and dismisses

## Export Format

JSON Lines (`.jsonl`):
```json
{"timestamp":"2026-10-23T00:05:32.123Z","appVersion":"1.0.0","appBuild":"42","iosVersion":"17.0","category":"launch","message":"Cold launch completed in 1.23s"}
{"timestamp":"2026-10-23T00:06:15.456Z","appVersion":"1.0.0","appBuild":"42","iosVersion":"17.0","category":"networkError","message":"HTTP 404 from api.example.com"}
```

## Usage from Code

Swift:
```swift
DiagnosticsManager.shared.logError(message: "Something failed")
DiagnosticsManager.shared.logNetworkError(statusCode: 404, url: "https://api.example.com/endpoint")
DiagnosticsManager.shared.logColdLaunchTime(1.23)
DiagnosticsManager.shared.logPlaybackStall(duration: 2.5)
```

Kotlin (iOS platform):
```kotlin
DiagnosticsLogger.logError("Something failed")
DiagnosticsLogger.logNetworkError(404, "https://api.example.com/endpoint")
```

The Kotlin logger is a thin wrapper that calls through to the Swift DiagnosticsManager.

## Tests

Run tests in Xcode:
1. Select test scheme
2. Cmd+U to run all tests

Tests cover:
- Redaction of tokens, API keys, emails, debrid links, URLs
- Toggle OFF deletes log file
- Log rotation at size cap
- JSON Lines format validity
- Crash detection
- Clear log functionality

## Implementation Notes

- All file I/O happens on a background queue (`DispatchQueue` with `.utility` QoS)
- Crash detection uses UserDefaults marker set on launch, cleared after 5 seconds
- Log rotation keeps newest 50% of entries when size exceeds ~4MB
- Export uses iOS native share sheet (AirDrop, Files, etc.)
- Settings integrate with native iOS Settings UI paradigms
- **iOS-specific**: All files are in iOS-only directories, not shared with Android or upstream
