#pragma once

// Implemented by @_cdecl in iosApp/iosApp/Diagnostics/DiagnosticsInterop.swift.
// Keep these signatures in sync with that file. C `int` is Swift CInt.

void NuvioDiagnosticsOpenSettings(void);
void NuvioDiagnosticsLogColdLaunchTime(double durationSeconds);
void NuvioDiagnosticsLogNetworkError(int statusCode, const char *url);
void NuvioDiagnosticsLogError(const char *message);
void NuvioDiagnosticsLogPlaybackStall(double durationSeconds);
