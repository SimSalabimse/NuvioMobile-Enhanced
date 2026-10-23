#pragma once

void NuvioDiagnosticsOpenSettings(void);
void NuvioDiagnosticsLogColdLaunchTime(double durationSeconds);
void NuvioDiagnosticsLogNetworkError(int statusCode, const char *url);
void NuvioDiagnosticsLogError(const char *message);
void NuvioDiagnosticsLogPlaybackStall(double durationSeconds);
