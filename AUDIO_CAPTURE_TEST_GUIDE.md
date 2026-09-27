# AVAsset Audio Capture Test Guide

## Overview

This document explains how to run automated tests to verify that AVAsset-based audio capture produces non-zero, varying energy samples on PR #13 branch (`cursor/autosync-capture-fix-20260925-9bb8`).

## Test Harness Location

```
iosApp/iosApp/Tests/AVAssetAudioCaptureTestHarness.swift
```

## What the Test Does

The test harness validates:

1. ✅ **Sample Count** - Captures at least 15 samples (~1.5 seconds)
2. ✅ **Mean Energy** - Average energy above silence floor (> 0.001)
3. ✅ **Variance** - Energy values vary (not flat zeros)
4. ✅ **Timestamps** - Sequential and increasing
5. ✅ **File Generation** - Creates test audio file successfully
6. ✅ **Capture Execution** - AVAsset capture completes without errors

## How to Run

### Method 1: Add Debug Menu (Recommended for Development)

Add a test trigger in your debug menu or settings:

```swift
// In your settings or debug menu
Button("Run Audio Capture Test") {
    let report = AVAssetAudioCaptureTestHarness.runAllTests()
    print("Test result: \(report.summary())")
}
```

### Method 2: Run on App Launch (Recommended for CI/Automated Testing)

Add to `AppDelegate` or app initialization:

```swift
#if DEBUG
override func applicationDidFinishLaunching(_ notification: Notification) {
    super.applicationDidFinishLaunching(notification)
    
    // Run test on launch
    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
        let report = AVAssetAudioCaptureTestHarness.runAllTests()
        InAppLogBridge.shared.info(
            tag: "TestRunner",
            message: "Audio capture test: \(report.summary())"
        )
    }
}
#endif
```

### Method 3: Manual Test Function Call

Call directly from any Swift code in the app:

```swift
let report = AVAssetAudioCaptureTestHarness.runAllTests()

if report.passed {
    print("✅ All tests passed!")
} else {
    print("❌ Tests failed:")
    report.failures.forEach { print("  - \($0)") }
}
```

## Reading Test Output

The test outputs detailed logs to `InAppLogBridge` with tag `AVAssetCaptureTest`.

### Success Output Example

```
========================================
AVAsset Audio Capture Test Harness
========================================

Test 1: Generating test audio file...
  ✅ Generated test audio file: test_audio_XXXX.caf

Test 2: Running AVAsset audio capture...
  ✅ Captured 20 energy samples

Test 3: Validating sample count...
  ✅ Sample count (20) >= minimum (15)

Test 4: Validating energy levels...
Mean energy: 0.350000
Max energy: 0.490000
Min energy: 0.210000
  ✅ Mean energy (0.350000) >= threshold (0.001000)

Test 5: Validating energy variance...
Variance: 0.00756000
  ✅ Variance (0.00756000) >= threshold (0.00001000)

Test 6: Validating timestamp sequence...
  ✅ All timestamps are sequential and increasing

========================================
Test Results: ✅ PASSED
Successes: 6 | Failures: 0
========================================
```

### Failure Output Example

```
========================================
AVAsset Audio Capture Test Harness
========================================

Test 1: Generating test audio file...
  ✅ Generated test audio file: test_audio_XXXX.caf

Test 2: Running AVAsset audio capture...
  ❌ AVAsset capture returned 0 samples

========================================
Test Results: ❌ FAILED
Successes: 1 | Failures: 1
========================================

Failures:
  - AVAsset capture returned 0 samples
```

## Test Configuration

You can adjust test parameters in `AVAssetAudioCaptureTestHarness.swift`:

```swift
private static let testDurationSeconds = 2.0          // Test audio length
private static let captureStartPositionMs: Int64 = 0  // Start position
private static let minExpectedSamples = 15            // Minimum samples
private static let minEnergyThreshold = 0.001         // Silence floor
private static let minVarianceThreshold = 0.00001     // Flatness threshold
```

## Running on Physical Device (SideStore)

1. **Build and sideload** the app with test harness included
2. **Launch the app**
3. **Trigger the test** using one of the methods above
4. **Check logs** via Xcode console or system logs:
   ```bash
   # View logs on device
   idevicesyslog | grep AVAssetCaptureTest
   ```

## Integration with CI/CD

### For GitHub Actions

Add a step to run the app with tests:

```yaml
- name: Run Audio Capture Tests
  run: |
    # Launch app on simulator
    xcrun simctl boot "iPhone 15 Pro"
    
    # Install app
    xcrun simctl install booted path/to/app.app
    
    # Launch app (test runs on launch if configured)
    xcrun simctl launch booted com.your.app.bundle.id
    
    # Wait for test to complete
    sleep 10
    
    # Extract logs
    xcrun simctl spawn booted log show --predicate 'subsystem == "AVAssetCaptureTest"' --last 1m
```

### For Manual Testing

1. Build in Xcode
2. Run on simulator or device
3. Trigger test via UI or auto-run
4. Check Xcode console for "PASSED" or "FAILED"

## Troubleshooting

### Test File Generation Fails

**Symptom:** "Failed to create audio format" or "Error creating audio file"

**Solution:** 
- Check that AVFoundation is available
- Verify temporary directory is writable
- Try on simulator first, then device

### Zero Samples Captured

**Symptom:** "AVAsset capture returned 0 samples"

**Possible causes:**
1. AVAssetReader couldn't open the file
2. No audio track found
3. Audio decode failed
4. Thread timing issue

**Debug steps:**
1. Check `InAppLogBridge` logs with tag `MPV/iOS/AudioCapture/AVAsset`
2. Look for "No audio track found" or "Failed to start AVAssetReader"
3. Verify test audio file was created successfully
4. Try increasing `captureWaitTime` in the test

### Low Energy or Flat Samples

**Symptom:** "Mean energy < threshold" or "Variance too low"

**Possible causes:**
1. Test audio generation issue
2. AVAsset decode producing silence
3. Wrong audio format interpretation

**Debug steps:**
1. Check "Audio format" log line for sample rate and channels
2. Verify test file plays correctly in QuickTime/VLC
3. Check if energy values are all zeros or just very low

### Timestamps Not Sequential

**Symptom:** "Timestamps are not properly sequential"

**Possible causes:**
1. Timing calculation bug
2. Sample rate mismatch
3. Sample ordering issue

**Debug steps:**
1. Print first few timestamps to verify calculation
2. Check sample rate matches expected 44100 Hz
3. Verify `processedSampleCount` increments correctly

## Expected Results

### On Simulator
- ✅ Should PASS all tests
- ✅ Generates test audio file
- ✅ Captures 15-20 samples
- ✅ Mean energy ~0.35 (tone amplitude)
- ✅ Variance ~0.007 (due to amplitude modulation)

### On Physical Device (SideStore)
- ✅ Should PASS all tests
- ✅ Same results as simulator
- ✅ Proves AVAsset works without ReplayKit entitlements

### If Tests Fail on SideStore

This would indicate a problem with the AVAsset implementation that needs investigation:
- Check iOS version compatibility
- Verify AVFoundation framework is available
- Check for any sideload-specific restrictions on AVAsset
- Review system logs for additional errors

## Success Criteria for PR #13

For this PR to be considered verified:

1. ✅ Test harness runs without crashing
2. ✅ Test audio file generates successfully
3. ✅ AVAsset capture produces samples (count > 0)
4. ✅ Mean energy > 0.001 (not silence)
5. ✅ Variance > 0.00001 (not flat)
6. ✅ Timestamps are sequential
7. ✅ **All tests PASS on SideStore physical device**

## Next Steps After Verification

Once tests pass:

1. Document test results in PR #13
2. Include test output logs
3. Note commit SHA where tests passed
4. If possible, include screenshots/video of test running on SideStore
5. Mark as ready for merge

## Support

If you encounter issues with the test harness:

1. Check `InAppLogBridge` logs for detailed error messages
2. Verify you're on the correct branch and commit
3. Try on simulator first to isolate platform issues
4. Review this guide's troubleshooting section
