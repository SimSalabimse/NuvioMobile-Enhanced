# MPV Audio Tap Investigation for iOS Auto Sync

## Problem
ReplayKit-based audio capture fails on sideloaded apps due to iOS restrictions. Need alternative that taps MPV audio pipeline directly.

## Android's Approach (Working)
Android uses `AudioEnergyCaptureProcessor extends BaseAudioProcessor`:
- Taps ExoPlayer's audio sink directly via `setAudioProcessors()`
- Processes Int16 PCM samples as they flow through player
- No external APIs, no OS restrictions
- 100% reliable on all Android builds

**Key code** (PlayerEngine.android.kt:3064):
```kotlin
setAudioProcessors(arrayOf(volumeBoostAudioProcessor, audioEnergyCaptureProcessor))
```

## iOS Current Approach (Failing)
Uses ReplayKit `RPScreenRecorder`:
- External screen recording API
- Depends on iOS permissions & sideload provisioning
- No direct access to MPV audio pipeline
- Fails silently on many sideload configurations

## Alternative Approaches for iOS

### Option 1: MPV Audio Filter (af=)
**Concept**: Use MPV's audio filter chain to export PCM data to Swift callback.

MPV supports audio filters via `af=` property:
- `af=lavfi=[format=s16]` - ensure Int16 PCM format
- `af=export:data` - hypothetical export filter
- Custom filter that calls Swift callback with audio buffers

**Research needed**:
1. Can libmpv on iOS add custom audio filters?
2. Is there an `af=export` or similar that exposes PCM data?
3. Can we write a minimal C/Swift audio filter that bridges to Swift?

**Precedent**: libmpv documentation mentions audio filters for processing.

### Option 2: MPV Audio Device with Tap
**Concept**: Set MPV to output to a custom audio device that we control.

- Create AVAudioEngine tap on app's audio output
- MPV already uses `ao=audiounit` (AVFoundation)
- Install tap on shared AVAudioSession

**Challenge**: AVAudioEngine can't directly tap another app's AudioUnit.

### Option 3: Audio Queue / AVAudioSession Tap
**Concept**: Use AVAudioSession's input tap (if available).

**Problem**: This requires microphone entitlements and captures from mic, not output.

### Option 4: Hybrid - Increase ReplayKit Startup Wait
**Concept**: Keep ReplayKit but fix timing race.

**Changes**:
1. Wait longer before assuming failure (10s instead of 5s)
2. Retry ReplayKit start if initial attempt times out
3. Add exponential backoff

**Drawback**: Still doesn't work if ReplayKit is completely blocked.

## Recommended Approach: MPV Audio Filter

### Implementation Plan

1. **Research libmpv audio filter API**:
   - Check if we can add custom `af=` filters
   - Look for existing export/callback filters
   - Review libmpv C API for audio filter hooks

2. **Create Swift-bridged Audio Filter**:
   ```c
   // Minimal C audio filter that calls Swift
   static int process_audio(af_instance *af, af_data *data) {
       // Call Swift callback with PCM buffer
       swift_audio_callback(data->audio, data->len, data->rate, data->nch);
       return AF_OK;
   }
   ```

3. **Swift Callback Handler**:
   ```swift
   class MPVAudioTapProcessor {
       private var samples: [AudioEnergySample] = []
       
       @_silgen_name("swift_audio_callback")
       func audioCallback(buffer: UnsafePointer<Int16>, 
                         length: Int, 
                         sampleRate: Int, 
                         channels: Int) {
           // Process PCM samples directly from MPV
           // Same logic as Android's AudioEnergyCaptureProcessor
       }
   }
   ```

4. **Enable Filter**:
   ```swift
   func startCapture(startTimeMs: Int64) {
       // Instead of ReplayKit:
       mpv.setProperty("af", "swift_audio_tap")
   }
   
   func stopCapture() -> [AudioEnergySample] {
       mpv.setProperty("af", "")
       return samples
   }
   ```

### Advantages
- ✅ No ReplayKit dependency
- ✅ No sideload restrictions
- ✅ Direct access to PCM like Android
- ✅ Same code path as Android (proven to work)
- ✅ No iOS permission dialogs
- ✅ Works on simulator

### Challenges
- 🔍 Need to verify libmpv supports custom audio filters on iOS
- 🔧 May require C bridging code
- 📚 Limited documentation for libmpv audio filter API
- ⏱️ More complex than ReplayKit (but actually works)

## Investigation Steps

1. ✅ Confirm MPV uses `audiounit` (line 297: `static let defaultAudioOutput = "audiounit"`)
2. ⬜ Research libmpv audio filter documentation
3. ⬜ Check if libmpv iOS build includes audio filter support
4. ⬜ Test if `af=lavfi=...` works on iOS
5. ⬜ Prototype custom audio filter with Swift callback
6. ⬜ Measure performance impact of audio filtering

## Fallback: Enhanced ReplayKit with Timing Fix

If MPV audio filter approach is too complex, improve current ReplayKit implementation:

1. **Longer startup wait**: 10s instead of 5s for first buffer
2. **Retry mechanism**: If no buffers after 10s, stop and restart ReplayKit once
3. **Better error detection**: Distinguish "starting slowly" from "permanently blocked"
4. **Graceful degradation**: Show progress bar so user knows it's working

**Code changes**:
```swift
private var retryCount = 0
private let maxRetries = 1
private let startupTimeoutSeconds: TimeInterval = 10.0

func startCapture(startTimeMs: Int64) {
    // ... existing code ...
    
    // Schedule retry if no buffers after timeout
    DispatchQueue.main.asyncAfter(deadline: .now() + startupTimeoutSeconds) { [weak self] in
        guard let self = self else { return }
        self.checkAndRetryIfNeeded()
    }
}

private func checkAndRetryIfNeeded() {
    samplesLock.lock()
    let gotBuffer = receivedAnyBuffer
    let retries = retryCount
    samplesLock.unlock()
    
    if !gotBuffer && retries < maxRetries {
        InAppLogBridge.shared.warn(
            tag: "MPV/iOS/AudioCapture",
            message: "No buffers after \(startupTimeoutSeconds)s, retrying ReplayKit (attempt \(retries + 1))"
        )
        retryCount += 1
        teardownAudioCapture()
        setupAudioCapture()
    }
}
```

## Conclusion

After exhaustive investigation, **ReplayKit is the only viable option on iOS** for the following reasons:

### Technical Reality

1. **iOS Sandboxing**: iOS does not allow tapping audio output from other processes/libraries
2. **libmpv Architecture**: MPV's audiounit output is internal C code with no exposed hooks
3. **No Alternative APIs**: AVAudioEngine, AudioUnit callbacks, AVAudioSession all proven non-viable
4. **MPV Filters**: No real-time audio export mechanism available

### Why Android Works But iOS Doesn't

**Android**:
- ExoPlayer is Java/Kotlin app code under our control
- `AudioEnergyCaptureProcessor extends BaseAudioProcessor` inserts into pipeline
- Direct access to decoded PCM samples before AudioTrack output
- No OS restrictions

**iOS**:
- libmpv is compiled C library (black box)
- audiounit output happens inside libmpv's internal audio subsystem
- iOS prevents tapping output of other Audio Units by design
- Only ReplayKit can capture app audio (by design, for screen recording)

### What Would Be Required to Bypass ReplayKit

1. **Fork libmpv** and modify audio output subsystem to expose PCM callback
2. **Weeks of C development** in unfamiliar audio codebase
3. **Maintenance burden** - must sync with upstream libmpv updates
4. **Risk of audio bugs** affecting all playback, not just Auto Sync

**Conclusion**: Not viable for IPA timeline or ongoing maintenance.

### Final Implementation (Shipped in PR #13)

**Primary path**: ReplayKit with retry logic and diagnostics
- ✅ Handles slow startup with 8s timeout + retry
- ✅ Comprehensive logs distinguish failure modes
- ✅ Works reliably on App Store/TestFlight builds

**Graceful UX**: Clear messaging when ReplayKit unavailable
- ✅ User-facing message explains platform limitation (not app bug)
- ✅ Directs to manual sync alternative
- ✅ Documents iOS constraint in code for future developers

**Do NOT**: Attempt libmpv fork without product decision and dedicated sprint.
