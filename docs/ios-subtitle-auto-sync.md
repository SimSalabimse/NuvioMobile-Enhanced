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
- ✅ Works with sideload builds (SideStore, AltStore)
- ✅ No microphone entitlements required
- ✅ Captures app audio output (not microphone input)
- ✅ Available since iOS 9.0
- ⚠️ Shows system recording indicator (acceptable trade-off)

**Alternative approaches considered:**
- ❌ AVAudioEngine tap: Can't tap MPV's AudioUnit output directly
- ❌ Microphone capture: Requires entitlements, poor quality (speaker → mic)
- ❌ MPV audio filter: Would require modifying libmpv

### Audio Processing

**Sample Processing:**
```swift
// 1. ReplayKit provides CMSampleBuffer with PCM audio
// 2. Extract Int16 samples from buffer
// 3. Mix stereo to mono if needed
// 4. Compute RMS energy over 4800 samples (~100ms window)
// 5. Store timestamped energy sample
```

**Energy Computation:**
```
RMS = sqrt(Σ(normalized_sample²) / sample_count)

Where:
- normalized_sample = sample / Int16.max
- Window size = 4800 samples (~100ms at 48kHz)
```

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
- **No subtitles selected**: Error message "Please select an external subtitle first"
- **Insufficient dialogue**: Error "Need at least 15s of dialogue for reliable sync"
- **Low confidence**: Warning "Low confidence sync. Offset: +1.2s. Try a scene with more dialogue."
- **Capture failure**: Error "Could not capture audio data. Please ensure playback is active."

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
| **Sample Format** | Int16 PCM | Int16 PCM |
| **Window Size** | 4800 samples (~100ms) | 4800 samples (~100ms) |
| **Energy Algorithm** | RMS | RMS |
| **Correlation Engine** | Shared `SubtitleAutoSyncEngine` | Shared `SubtitleAutoSyncEngine` |

Both implementations produce identical energy samples, ensuring cross-platform consistency.

## Known Limitations

1. **Recording Indicator**: iOS shows system recording indicator during capture (by design)
2. **Background Capture**: May not work if app is backgrounded during capture
3. **Audio Route Changes**: Switching audio output (BT headphones) during capture may cause issues
4. **Low Volume**: Very quiet audio may not provide enough signal for correlation

## Future Improvements

- [ ] Add progress percentage during capture
- [ ] Support background capture
- [ ] Cache audio samples for retry without recapture
- [ ] Optimize for low-power mode
- [ ] Add unit tests with mock audio data

## Troubleshooting

### "Could not capture audio data"
- Ensure video is playing (not paused)
- Check device volume is not muted
- Verify ReplayKit is available (should work on iOS 9+)

### Low Confidence Results
- Try a scene with more dialogue
- Ensure subtitle language roughly matches audio language
- Check subtitle file is not severely out of sync (>30s offset)

### Recording Indicator Won't Disappear
- Force-quit app and restart
- This is rare but can happen if capture cleanup fails

## References

- [Apple ReplayKit Documentation](https://developer.apple.com/documentation/replaykit)
- [SubtitleAutoSyncEngine.kt](../composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAutoSyncEngine.kt)
- [MPVAudioCaptureProcessor.swift](../iosApp/iosApp/Player/MPVAudioCaptureProcessor.swift)
