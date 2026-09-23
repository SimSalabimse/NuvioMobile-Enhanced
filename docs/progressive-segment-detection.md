# Progressive Live Auto-Segmenting

## Overview

Progressive Live Auto-Segmenting automatically detects intro/recap/credits/preview boundaries during playback by analyzing audio energy patterns in real-time. Unlike traditional segment detection that requires downloading the entire file, this feature works progressively as the user watches.

## Key Features

- **Progressive Capture**: Continuously captures audio energy during playback without requiring full file download
- **Late Intro Detection**: Can detect intros that appear several minutes into the episode (e.g., Stranger Things S5E3 with intro at ~6 minutes)
- **Non-Blocking UX**: Shows toast-style notifications with detected segments; user confirms before submitting
- **Platform Support**: Works on both iOS (via ReplayKit) and Android (via ExoPlayer AudioProcessor)
- **IntroDB Integration**: Detected segments can be submitted directly to TheIntroDB/IntroDB

## Architecture

### Components

1. **ProgressiveSegmentDetector** (`skip/ProgressiveSegmentDetector.kt`)
   - Core detection algorithm using energy-based edge detection
   - Identifies music→dialogue transitions (intros/recaps)
   - Identifies dialogue→music/silence transitions (credits)
   - Returns candidates sorted by confidence

2. **ProgressiveSegmentSession** (`skip/ProgressiveSegmentSession.kt`)
   - Manages session state during playback
   - Accumulates audio samples over time
   - Triggers periodic analysis (every 30 seconds)
   - Emits detected segments as StateFlow

3. **Audio Capture Extensions**
   - `MPVAudioCaptureProcessor.swift` (iOS): Extended with progressive mode
   - `AudioEnergyCaptureProcessor` (Android): Extended with progressive mode
   - Both support `getNewSamples()` for incremental retrieval

4. **UI Components**
   - `DetectedSegmentNotification.kt`: Toast-style notification
   - Integrated with existing `SubmitIntroDialog.kt`

5. **Player Integration**
   - `PlayerScreenRuntimeProgressiveSegment.kt`: Lifecycle management
   - Starts after initial playback load completes
   - Stops when playback ends or source changes

## Detection Algorithm

### Energy Analysis

The detector analyzes audio energy in 500ms windows and identifies significant edges (transitions):

- **High-energy music** followed by **lower-energy dialogue** → Likely intro/recap
- **Dialogue** followed by **ending music/silence** → Likely credits
- **Position-aware**: Considers segment placement (early/mid/late episode)

### Confidence Scoring

Segments are scored based on:
- **Duration**: Prefer 20-90s segments (typical intro length)
- **Energy drop**: Clear transitions score higher
- **Position**: Intros typically 0-10 minutes in; credits near end
- **Minimum confidence**: 0.4 threshold for notification

### Example Detection

```
Episode: 45 minutes
Detected: Intro 6:12 → 7:45 (confidence: 0.72)
Reason: music→dialogue transition
```

## User Flow

1. User plays episode/movie with progressive detection enabled
2. Playback starts, audio energy capture begins automatically
3. Every 30 seconds, accumulated samples are analyzed
4. When high-confidence segment is detected:
   - Toast notification appears: "Detected intro ~6:12 → 7:45?"
   - User can tap to open flag/submit dialog (times pre-filled)
   - Or dismiss notification if incorrect
5. User confirms and submits to IntroDB

## Settings

**Location**: Settings → Playback → Progressive Segment Detection

- **Enable/Disable**: `progressiveSegmentDetectionEnabled` (default: `false`)
- Safe behind flag for initial deployment
- Works independently of existing skip intro features

## Platform Details

### iOS (ReplayKit)

- Uses existing `MPVAudioCaptureProcessor.swift`
- Extended with `progressive` parameter and `getNewSamples()`
- Shows system recording indicator (acceptable trade-off)
- No additional entitlements required

### Android (ExoPlayer)

- Uses existing `AudioEnergyCaptureProcessor`
- Extended with `progressive` parameter and `getNewSamples()`
- Direct PCM capture from playback pipeline
- No user indicators or permissions needed

## Limitations

1. **Audio-only**: Relies on audio energy patterns; may miss visual-only intros
2. **Confidence threshold**: May miss very subtle transitions
3. **Late notification**: Segments detected ~30s after they occur (analysis interval)
4. **Session-based**: Does not persist across app restarts
5. **No cross-episode**: Each session is independent (no fingerprinting)

## Performance

- **Capture overhead**: Minimal; reuses subtitle Auto Sync infrastructure
- **Analysis frequency**: Every 30 seconds (configurable)
- **Sample storage**: ~1 sample per 100ms (~600 samples/minute)
- **Memory**: Negligible for typical episode lengths

## Testing

### Manual Test Procedure

#### Prerequisites
- Build and install IPA/APK
- Enable "Progressive Segment Detection" in Settings → Playback
- Find a show with a late or mid-episode intro (e.g., Stranger Things S5E3)

#### Test Steps

1. **Start playback** of episode with known late intro
2. **Watch continuously** past the intro (don't skip manually)
3. **Wait 30-60 seconds** after intro ends
4. **Verify**: Toast notification appears with detected intro times
5. **Tap notification** → Submit dialog opens with pre-filled times
6. **Verify times** are reasonably close to actual intro
7. **Submit** to IntroDB (if enabled)

#### Expected Results

- Notification appears within 30-60s of segment ending
- Start/end times are within ±10 seconds of actual segment
- Confidence score is > 0.4
- Subtitle Auto Sync still works (capture paths don't conflict)

#### Edge Cases to Test

- **No intro**: Should not show false positives
- **Very short intro**: (<15s) Should be filtered out
- **Very long intro**: (>2.5 min) Should be detected
- **Multiple segments**: Should show highest confidence first
- **Pause/resume**: Session should continue correctly
- **Source change**: Should stop old session, start new one

## Future Enhancements

- [ ] Adjustable confidence threshold in settings
- [ ] Support for preview/post-credits detection
- [ ] Background analysis (lower priority)
- [ ] Cross-episode fingerprinting for better accuracy
- [ ] Machine learning model for improved detection
- [ ] Real-time notification (detect while segment is playing)

## References

- Subtitle Auto Sync: `docs/ios-subtitle-auto-sync.md`
- IntroDB/TheIntroDB: `skip/SkipIntroRepository.kt`
- Energy Analysis: Similar to subtitle sync correlation
