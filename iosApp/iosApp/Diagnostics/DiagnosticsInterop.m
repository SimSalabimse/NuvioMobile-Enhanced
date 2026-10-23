#import <Foundation/Foundation.h>

#include "../../../composeApp/src/nativeInterop/cinterop/DiagnosticsInterop.h"

@interface DiagnosticsBridge : NSObject
+ (void)openDiagnosticsSettings;
+ (void)logColdLaunchTime:(double)duration;
+ (void)logNetworkErrorWithStatusCode:(NSInteger)statusCode url:(NSString *)url;
+ (void)logError:(NSString *)message;
+ (void)logPlaybackStall:(double)duration;
@end

static NSString *NuvioDiagnosticsString(const char *value) {
    if (value == NULL) {
        return @"";
    }
    NSString *string = [NSString stringWithUTF8String:value];
    return string == nil ? @"" : string;
}

void NuvioDiagnosticsOpenSettings(void) {
    [DiagnosticsBridge openDiagnosticsSettings];
}

void NuvioDiagnosticsLogColdLaunchTime(double durationSeconds) {
    [DiagnosticsBridge logColdLaunchTime:durationSeconds];
}

void NuvioDiagnosticsLogNetworkError(int statusCode, const char *url) {
    [DiagnosticsBridge logNetworkErrorWithStatusCode:(NSInteger)statusCode
                                                  url:NuvioDiagnosticsString(url)];
}

void NuvioDiagnosticsLogError(const char *message) {
    [DiagnosticsBridge logError:NuvioDiagnosticsString(message)];
}

void NuvioDiagnosticsLogPlaybackStall(double durationSeconds) {
    [DiagnosticsBridge logPlaybackStall:durationSeconds];
}
