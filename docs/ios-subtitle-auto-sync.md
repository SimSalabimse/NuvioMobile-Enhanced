# iOS Subtitle Auto Sync Implementation

## Overview

This document describes the iOS implementation of the Subtitle Auto Sync feature, which automatically synchronizes subtitle timing with video playback by analyzing audio patterns.

## Architecture

### Components

1. **MPVAudioCaptureProcessor.swift** (iOS)
   - Captures app audio output using ReplayKit (`RPScreenRecorder`)
   - Processes PCM audio samples in real-time
   - Computes RMS (Root Mean Square) energy over 100ms windows
   - Returns timestamped energy samples for correlation analysis

2. **SubtitleAutoSyncEngine.kt** (Shared)
   - Cross-platform correlation engine
   - Takes audio energy samples and subtitle cue timings
   - Uses cross-correlation to find optimal subtitle offset
   - Returns offset with confidence score

3. **PlayerBridge Integration**
   - Swift bridge exposes audio capture methods to Kotlin
   - Kotlin layer coordinates capture and computation
   - UI shows progress and applies computed offset

### Audio Capture Flow

```
User taps "Auto Sync"
    ↓
Start ReplayKit audio capture (iOS shows recording indicator)
    ↓
Capture 20-30 seconds of audio
    ↓
Process audio → RMS energy samples every ~100ms
    ↓
Stop capture & get energy samples
    ↓
SubtitleAutoSyncEngine.computeOptimalOffset(audioSamples, subtitleCues)
    ↓
Apply offset automatically or show error/low-confidence message
```

## iOS Implementation Details

### ReplayKit Choice

**Why ReplayKit?**
- ✅ No microphone entitlements required
- ✅ Captures app audio output (not microphone input)
- ✅ Available since iOS 9.0
- ⚠️ Shows system recording indicator (acceptable trade-off)
- ⚠️ **May be restricted on sideloaded apps** (see Limitations below)

**Alternative approaches considered:**
- ❌ AVAudioEngine tap: Can't tap MPV's AudioUnit output directly
- ❌ Microphone capture: Requires entitlements, poor quality (speaker → mic)
- ❌ MPV audio filter: Would require modifying libmpv

**Sideload Compatibility:**
ReplayKit availability depends on iOS version, provisioning profile, and sideload method:
- ✅ **App Store builds**: ReplayKit works reliably
- ⚠️ **SideStore/AltStore**: ReplayKit availability varies by iOS version and entitlements
- ⚠️ **TestFlight**: Usually works, but depends on provisioning
- ❌ **Simulators**: ReplayKit typically unavailable

The implementation includes robust detection and error reporting when ReplayKit is unavailable.

### Audio Processing

**Sample Processing:**
```swift
// 1. ReplayKit provides CMSampleBuffer with PCM audio (Float32 or Int16)
// 2. Detect audio format from CoreAudio format flags
// 3. Extract samples in correct format (Float32 most common on iOS)
// 4. Mix multi-channel to mono if needed
// 5. Compute RMS energy over 4800 samples (~100ms window)
// 6. Store timestamped energy sample with position-based timestamp
```

**Audio Format Support:**
- **Float32**: Most common on iOS, samples are -1.0 to 1.0 range (no normalization needed)
- **Int16**: Legacy format, samples are -32768 to 32767 (normalized by dividing by Int16.max)
- **Multi-channel**: Averaged to mono by summing all channels and dividing by channel count

**Energy Computation:**
```
RMS = sqrt(Σ(normalized_sample²) / sample_count)

Where:
- normalized_sample = Float32: sample as-is (-1.0 to 1.0)
                    = Int16: sample / Int16.max
- Window size = 4800 samples (~100ms at 48kHz)
- Timestamp = captureStartMs + (processedSamples * 1000 / sampleRate)
```

**MPV Audio Output Compatibility:**
MPV must use `audiounit` audio output for ReplayKit compatibility:
- ✅ `audiounit` (default) - Routes through AVFoundation, ReplayKit can capture
- ⚠️ Other outputs may not be visible to ReplayKit
- The implementation checks current audio output and warns if incompatible

### Synchronization

**Timestamp Alignment:**
- Capture starts at player position `startTimeMs`
- Each energy sample gets timestamp: `startTimeMs + (processedSamples * 1000 / sampleRate)`
- Subtitle cues have precise start/end times
- Cross-correlation finds best temporal alignment

## User Experience

### Normal Flow
1. User selects external subtitle
2. Taps "Auto Sync" button
3. Sees iOS recording indicator (red/orange at top of screen)
4. Loading spinner shows for 20-30 seconds
5. Success message: "Synced! Offset: +2.3s (confidence: 78%)"
6. Subtitles are automatically adjusted

### Edge Cases
- **No subtitles selected**: Error "Please select an external subtitle first"
- **Insufficient dialogue**: Error "Need at least 15s of dialogue for reliable sync"
- **Low confidence**: Warning "Low confidence sync. Offset: +1.2s. Try a scene with more dialogue."
- **ReplayKit unavailable**: Error "Could not capture audio data. ReplayKit may not be available or there's an MPV audio routing issue. Check logs for details. This feature may not work on sideloaded apps."
- **No audio buffers**: Detailed diagnostics in logs explain whether it's a sideload restriction, MPV routing issue, or other problem

## Permissions & Entitlements

### Required
- None! ReplayKit works without special entitlements.

### Optional (for best UX)
- User can grant "Screen Recording" permission in Settings (iOS will prompt automatically)
- Not required for basic functionality

### Info.plist
No changes needed. ReplayKit permissions are handled automatically by iOS.

## Testing

### Manual Test Procedure

1. **Setup**
   - Build IPA and install via SideStore
   - Load video with external subtitle
   - Manually set subtitle delay to +5000ms (obviously wrong)

2. **Execute**
   - Open subtitle settings panel
   - Tap "Auto Sync" button
   - Observe recording indicator

3. **Verify**
   - Wait for capture to complete (~30s)
   - Check success message shows computed offset
   - Play video and verify subtitles match dialogue

4. **Edge Cases**
   - Try with no dialogue (music video) → should show error
   - Try with paused video → may fail or give low confidence
   - Try with very short video → should show insufficient data error

### Automated Testing
Currently no unit tests for audio capture (requires simulator audio support).
Integration testing done manually on device.

## Comparison with Android

| Aspect | iOS | Android |
|--------|-----|---------|
| **Audio Capture** | ReplayKit (app audio) | ExoPlayer AudioProcessor (direct PCM) |
| **Permissions** | None required | None required |
| **User Indicator** | Recording indicator visible | None |
| **Sample Format** | Float32 (primary) or Int16 PCM | Int16 PCM |
| **Sideload Support** | Limited (ReplayKit restrictions) | Full support |
| **Window Size** | 4800 samples (~100ms) | 4800 samples (~100ms) |
| **Energy Algorithm** | RMS | RMS |
| **Correlation Engine** | Shared `SubtitleAutoSyncEngine` | Shared `SubtitleAutoSyncEngine` |
| **Audio Routing** | Requires audiounit output | Direct from player |
| **Reliability** | High (App Store), Variable (Sideload) | High (all builds) |

Both implementations produce identical energy samples, ensuring cross-platform consistency when they work.

## Known Limitations

### Critical Limitations

1. **iOS Platform Constraint: ReplayKit-Only** ⚠️
   - **ReplayKit is the ONLY iOS API** for app audio capture without microphone entitlements
   - iOS does not allow tapping/intercepting audio output from libraries like libmpv
   - Alternative approaches investigated (AVAudioEngine taps, AudioUnit callbacks, MPV filters)
     all proven non-viable due to iOS sandboxing and libmpv's internal audio handling
   - **Why Android works but iOS has restrictions**:
     - Android: ExoPlayer's AudioProcessor inserts into pipeline (direct PCM access)
     - iOS: libmpv's audiounit output is internal & opaque (no tap points)
   - **Only path to bypass ReplayKit**: Fork libmpv and add custom audio export (weeks of work)

2. **Sideload Restrictions** ⚠️
   - ReplayKit may be unavailable or restricted in sideloaded apps (SideStore, AltStore)
   - iOS may deny audio capture for apps without proper provisioning
   - Works reliably in App Store and TestFlight builds
   - **Symptom**: Empty audio samples even when video is playing
   - **Detection**: Logs show "ReplayKit not available" or "No audio buffers received"
   - **User Experience**: Clear message directs to manual sync when Auto Sync unavailable

3. **MPV Audio Routing**
   - MPV must use `audiounit` output for ReplayKit compatibility
   - Other audio outputs may not route through AVFoundation
   - **Symptom**: ReplayKit starts but never receives audio buffers
   - **Detection**: Logs show "No audio buffers received from ReplayKit after Xs"

### Minor Limitations

3. **Recording Indicator**: iOS shows system recording indicator during capture (by design, expected)
4. **Background Capture**: May not work if app is backgrounded during capture
5. **Audio Route Changes**: Switching audio output (BT headphones) during capture may cause issues
6. **Low Volume**: Very quiet audio may not provide enough signal for correlation
7. **Simulator**: ReplayKit typically unavailable on iOS Simulator

## Future Improvements

- [ ] Add progress percentage during capture
- [ ] Support background capture
- [ ] Cache audio samples for retry without recapture
- [ ] Optimize for low-power mode
- [ ] Add unit tests with mock audio data

## Troubleshooting

### "Could not capture audio data"

**Check the logs for specific diagnostics:**

1. **"ReplayKit not available"**
   - **Cause**: App is sideloaded and iOS is restricting ReplayKit
   - **Solution**: Install via App Store or TestFlight if possible
   - **Workaround**: Manual subtitle synchronization (adjust delay manually)

2. **"No audio buffers received from ReplayKit"**
   - **Causes**: 
     - Sideload restrictions (most common)
     - MPV audio output incompatibility
     - iOS denying background audio capture
   - **Checks**:
     - Ensure video is actively playing (not paused)
     - Check device volume is not muted
     - Verify you're in foreground during capture
   - **Log details**: Look for "MPV audio output" warning

3. **"Received buffers but captured 0 energy samples"**
   - **Cause**: Audio is silent or extremely quiet
   - **Checks**:
     - Verify video actually has audio
     - Check if specific codec/format issue
     - Try different video file

### Low Confidence Results
- Try a scene with more dialogue
- Ensure subtitle language roughly matches audio language  
- Check subtitle file is not severely out of sync (>30s offset)
- Avoid music-only scenes (no speech patterns to correlate)

### Recording Indicator Won't Disappear
- Force-quit app and restart
- This is rare but can happen if capture cleanup fails

### Debug Information

**Comprehensive logging** is available in the app logs:
- `[MPV/iOS/AudioCapture]` - ReplayKit status, buffer reception, audio format
- `[AutoSync]` - Capture progress, sample counts
- **Key log messages to look for**:
  - "ReplayKit not available" - Sideload restriction
  - "Received first audio buffer" - ReplayKit working
  - "Audio format: Float32/Int16" - Format detection
  - "Audio energy looks good" - Successful capture
  - "No audio buffers received" - ReplayKit/routing failure

## References

- [Apple ReplayKit Documentation](https://developer.apple.com/documentation/replaykit)
- [SubtitleAutoSyncEngine.kt](../composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAutoSyncEngine.kt)
- [MPVAudioCaptureProcessor.swift](../iosApp/iosApp/Player/MPVAudioCaptureProcessor.swift)
