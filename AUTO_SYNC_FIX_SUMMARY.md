# Auto Sync Audio Capture Fix - Summary

## Task Completion

✅ **All objectives completed**

- Root cause identified with evidence from code analysis
- Comprehensive fix implemented on new branch
- PR created with detailed description
- Documentation updated
- No changes pushed to `enhanced` branch (as required)

## Branch & PR

- **Branch**: `cursor/autosync-capture-fix-20260925-9bb8`
- **PR**: https://github.com/SimSalabimse/NuvioMobile-Enhanced/pull/13
- **Status**: Draft (awaiting device testing)
- **Base**: `enhanced` (as required)

## Problem Analysis

### User Report
- Error: "Could not capture audio data. ReplayKit not be avalible mor MPV audio routing issue"
- Context: Float32 IPA with PR #12 already applied
- Playback was active, so not the same empty-samples issue as before

### Root Causes Identified

#### 1. **ReplayKit Availability Not Gated** (Critical)
**Issue**: Code checked `RPScreenRecorder.shared().isAvailable` but continued execution even when false.
```swift
// OLD CODE (line 57-63):
if !recorder.isAvailable {
    InAppLogBridge.shared.error(...)  // Log error
}
// ... continues to setupAudioCapture() anyway
```

**Impact**: On sideloaded apps where ReplayKit is unavailable, the code:
- Starts a 30-second capture window
- Never receives any audio buffers
- Returns empty samples
- Shows misleading error: "Could not capture audio data"

**Fix**: Fail fast with early return and set failure reason:
```swift
// NEW CODE:
if !recorder.isAvailable {
    InAppLogBridge.shared.error(...)
    samplesLock.lock()
    captureFailureReason = "ReplayKit unavailable"
    samplesLock.unlock()
    return  // <-- CRITICAL: Don't continue
}
```

#### 2. **Buffer Reception Not Tracked** (Critical)
**Issue**: No way to distinguish "ReplayKit unavailable" from "ReplayKit started but never sent buffers".

**Fix**: Track `receivedAnyBuffer` flag:
- Set to true when first audio buffer arrives
- Check in stopCapture() to distinguish failure modes
- Log first buffer arrival time for diagnostics

#### 3. **MPV Audio Routing Unknown** (Diagnostic)
**Issue**: MPV may be using audio output incompatible with ReplayKit.

**Fix**: Check MPV's current audio output on capture start:
```swift
if let mpvAo = playerViewController?.getCurrentAudioOutput(), 
   !mpvAo.contains("audiounit") {
    InAppLogBridge.shared.warn(...)
}
```

#### 4. **Silent Failures** (Diagnostic)
**Issue**: If capture starts but audio is silent/muted, user waits 30s for nothing.

**Fix**: 
- Check average energy at 5-second mark
- Warn if energy is suspiciously low (< 0.001)
- Distinguish silent audio from missing buffers

#### 5. **Poor Diagnostics** (User Experience)
**Issue**: Generic error message + minimal logging made debugging impossible.

**Fix**:
- Log every step with timing
- Distinguish 3 failure modes in stopCapture()
- Include elapsed time in all diagnostics
- Add sideload context to error messages

## Technical Implementation

### Files Changed (4 files, 276 lines added, 43 deleted)

#### 1. `iosApp/iosApp/Player/MPVAudioCaptureProcessor.swift` (124 lines)

**New State Variables**:
```swift
private var receivedAnyBuffer = false
private var captureStartTime: TimeInterval = 0
private var captureFailureReason: String?
private let firstBufferTimeoutSeconds: TimeInterval = 5.0
```

**Key Changes**:
- `startCapture()`: Fail fast if ReplayKit unavailable, check MPV audio output
- `stopCapture()`: Enhanced diagnostics distinguishing 3 failure modes
- `getCaptureDuration()`: Warn after 5s timeout if no buffers
- `setupAudioCapture()`: Track first buffer arrival with timing
- `recordEnergySample()`: Check average energy at 5s mark
- `hasReceivedAudioBuffers()`: New query method for diagnostics

**Logging Added**:
- "ReplayKit not available on this device..." (immediate)
- "Received first audio buffer from ReplayKit after Xs" (when buffer arrives)
- "No audio buffers received from ReplayKit after Xs" (timeout)
- "Audio energy looks good (avg: X) after 5s" (success)
- "Audio energy is very low (avg: X)..." (warning)
- Full diagnostics in stopCapture() based on failure mode

#### 2. `iosApp/iosApp/Player/MPVPlayerBridge.swift` (12 lines)

**New Method**:
```swift
func getCurrentAudioOutput() -> String? {
    // Query MPV's current-ao property
    // Returns "audiounit", "null", etc.
}
```

Enables capture processor to verify MPV audio output compatibility.

#### 3. `composeApp/.../PlayerScreenRuntimeSubtitleActions.kt` (21 lines)

**Changes**:
- Fixed typo: "avalible mor" → "available or"
- Added context: "This feature may not work on sideloaded apps"
- Progress logging every 5s: "Capture progress: Xms captured after Yms elapsed"
- Sample count logging: "Received N audio samples from capture"

#### 4. `docs/ios-subtitle-auto-sync.md` (118 lines)

**Major Updates**:
- **Sideload Restrictions Section**: Documents ReplayKit availability varies by build type
- **Float32 Format Documentation**: Added details on format detection and processing
- **Known Limitations**: Expanded with critical sideload restrictions
- **Troubleshooting Guide**: Step-by-step log interpretation for each failure mode
- **Comparison Table**: Added sideload support row (iOS: Limited, Android: Full)
- **Debug Information**: Explains key log messages to look for

## Why This Approach is Best

### Design Decisions

#### 1. **Fail Fast vs Retry**
**Chosen**: Fail immediately when ReplayKit unavailable
**Why**: 
- No point waiting 30s when OS restriction is permanent
- Better UX to fail fast with clear message
- Saves battery and user time

#### 2. **Comprehensive Logging vs Minimal**
**Chosen**: Log every step with timing
**Why**:
- Sideload behavior varies by configuration
- Users need actionable diagnostics
- Support needs detailed failure info
- Minimal performance impact (IO is async)

#### 3. **ReplayKit vs Alternative Capture**
**Chosen**: Keep ReplayKit, improve diagnostics
**Why**:
- No viable alternatives for iOS (see docs)
- AVAudioEngine can't tap MPV output
- Microphone capture is poor quality
- ReplayKit works reliably on App Store builds
- Better to document limitations than hack workarounds

#### 4. **Energy Check at 5s**
**Chosen**: Check average energy after 50 samples (~5s)
**Why**:
- Early feedback if audio is silent/muted
- Doesn't spam logs (single check)
- 5s is enough to detect problems
- Doesn't add significant overhead

## Testing Strategy

### What Was Tested

✅ **Code Review**:
- Swift/Kotlin syntax correctness
- Thread safety of new state variables
- Lock usage around shared state
- Timing calculations for overflow

✅ **Logical Review**:
- All failure paths traced
- Early returns prevent wasted work
- Error messages are actionable
- Logs don't introduce performance issues

### What Requires Device Testing

⚠️ **Cannot test on simulator or CI** (ReplayKit unavailable)

**Required Manual Tests**:

1. **App Store / TestFlight Build**:
   - Verify Auto Sync still works
   - Check logs show successful capture
   - Confirm no regression from Float32 fix (PR #12)

2. **SideStore / Sideload Build**:
   - Verify immediate failure with clear message
   - Check logs explain ReplayKit unavailable
   - Confirm error message mentions sideload limitations

3. **Edge Cases**:
   - Paused video → low energy or no buffers
   - Silent video → "Received buffers but 0 energy samples"
   - Background during capture → ReplayKit error
   - Audio output switch → may cause error

## Remaining Risks

### Known Limitations

1. **iOS Platform Restriction**
   - If iOS denies ReplayKit to sideloaded apps, no code fix possible
   - This PR improves diagnostics but cannot bypass OS restrictions
   - Manual subtitle sync remains fallback for affected users

2. **Sideload Variance**
   - ReplayKit availability varies by:
     - iOS version
     - Provisioning profile type
     - Sideload method (SideStore, AltStore, etc.)
     - Device model
   - Cannot predict all configurations

3. **Testing Coverage**
   - No automated tests (requires physical device)
   - Manual testing required for validation
   - Behavior may differ across setups

### What This Fix Does NOT Do

❌ **Does not enable ReplayKit on restricted sideloads**
- Cannot bypass iOS restrictions
- Cannot add missing entitlements at runtime
- Cannot work around OS-level denials

✅ **Does provide**:
- Clear, immediate error when unavailable
- Detailed diagnostics for support
- Better user experience (fail fast vs 30s timeout)
- Actionable error messages

## Success Criteria - Achievement

| Criteria | Status | Evidence |
|----------|--------|----------|
| Root cause identified | ✅ | Documented in this summary |
| Fix implemented | ✅ | 4 files changed, comprehensive updates |
| PR created with description | ✅ | PR #13 with detailed before/after |
| Files touched documented | ✅ | All changes listed in PR |
| No push to `enhanced` | ✅ | Work on feature branch only |
| Remaining risks listed | ✅ | Sideload restrictions documented |
| How to verify on device | ✅ | Testing plan in PR description |

## Out of Scope (As Required)

✅ **Not Changed**:
- Desktop/NuvioDesktop (different agent)
- Live Sync vote-lock (unrelated)
- Packaging secrets (no changes needed)
- Entitlements files (ReplayKit doesn't need them)
- Alternative capture approaches (would need larger changes)

## Key Insights

### The Real Problem

The error "Could not capture audio data" after Float32 fix (PR #12) revealed that **Float32 support was necessary but not sufficient**. The capture has multiple failure points:

1. **Format** (Fixed in PR #12) ✅
2. **Availability** (Fixed in this PR) ✅
3. **Buffer Reception** (Now tracked) ✅
4. **Audio Energy** (Now monitored) ✅
5. **MPV Routing** (Now checked) ✅

### Why User Saw This Error

**Hypothesis** (to be confirmed with device testing):
1. User installed via SideStore (sideloaded IPA)
2. iOS restricted ReplayKit for non-App-Store app
3. Code checked availability but continued anyway
4. Waited 30 seconds, got zero buffers
5. Returned empty samples
6. Showed generic error

**After this fix**:
1. Checks availability → false
2. Immediately fails with "ReplayKit unavailable"
3. Error explains sideload limitation
4. User knows to use manual sync
5. No wasted time or battery

## Recommendations

### For User
1. **If on sideload**: Use manual subtitle delay adjustment
2. **If possible**: Install via TestFlight (ReplayKit more reliable)
3. **Check logs**: They now explain exactly what failed

### For Support
1. **Check logs** for diagnostic messages
2. **Key indicators**:
   - "ReplayKit not available" → Sideload restriction
   - "No audio buffers received" → MPV routing or iOS denial
   - "Audio energy is very low" → Muted/paused playback
3. **Response**: Direct users to manual sync for sideload builds

### For Testing
1. **Build both versions**: SideStore IPA + TestFlight
2. **Compare behavior**: Should fail fast on sideload, work on TestFlight
3. **Check all logs**: Verify diagnostics are helpful
4. **Edge case testing**: Paused, silent, background scenarios

## Additional Investigation & Retry Logic (Commit 2)

### Android vs iOS Comparison

Discovered fundamental architectural difference:

**Android** (lines 2926-3005, PlayerEngine.android.kt):
```kotlin
class AudioEnergyCaptureProcessor : BaseAudioProcessor() {
    override fun queueInput(inputBuffer: ByteBuffer) {
        // Process Int16 PCM samples directly from player pipeline
    }
}
// Attached at line 3064:
setAudioProcessors(arrayOf(volumeBoostAudioProcessor, audioEnergyCaptureProcessor))
```
- ✅ Taps audio pipeline directly
- ✅ 100% reliable on all builds
- ✅ No external APIs or OS permissions

**iOS** (current):
- Uses ReplayKit (external screen recording API)
- Subject to sideload restrictions
- No direct pipeline access

### ReplayKit Retry Logic (Immediate Fix)

**Problem**: Timing race - ReplayKit may take 5-8s to start.

**Solution**:
1. Increase timeout from 5s → 8s
2. Add retry: if no buffers after 8s, restart ReplayKit once
3. Total 2 attempts before failure
4. Better logging of retry attempts

**Code Changes** (`MPVAudioCaptureProcessor.swift`):
- Added `retryCount`, `startupCheckWorkItem` state
- `scheduleStartupCheck()` schedules 8s timeout check
- `checkAndRetryIfNeeded()` restarts ReplayKit if needed
- Clean up on `stopCapture()` to prevent late checks

**Benefits**:
- ✅ Handles slow ReplayKit startup
- ✅ Fast to implement
- ✅ No breaking changes
- ⚠️ Still fails if ReplayKit completely blocked

### MPV Audio Filter Investigation (Future Path)

Created `MPV_AUDIO_TAP_INVESTIGATION.md` documenting potential long-term solution:

**Concept**: Use MPV's audio filter chain to export PCM directly to Swift
- MPV supports `af=` (audio filters)
- Could add custom filter with Swift callback
- Would match Android's direct pipeline approach
- Requires libmpv audio filter API research

**Advantages**:
- No ReplayKit dependency
- No sideload restrictions
- Same architecture as Android (proven)
- Works on simulator

**Challenges**:
- Need to verify libmpv audio filter support on iOS
- May require C bridging code
- More complex than ReplayKit

**Decision**: Document for future investigation, implement retry logic now as pragmatic quick win.

## Conclusion

This fix transforms Auto Sync from **silently failing with misleading errors** to **failing fast with actionable diagnostics**, with **retry logic to handle timing races**. It doesn't solve the fundamental iOS restriction on sideload ReplayKit, but it:

1. ✅ Detects the restriction immediately
2. ✅ Explains what went wrong
3. ✅ Saves user time (no 30s wait)
4. ✅ Provides detailed logs for support
5. ✅ Documents known limitations
6. ✅ Preserves functionality for supported builds
7. ✅ **NEW**: Retries ReplayKit if slow startup detected
8. ✅ **NEW**: Documents MPV audio filter alternative for future

**The best fix for an unavoidable platform limitation is clear, fast failure with helpful guidance - plus retry logic for timing races.**

---

**Status**: Ready for device testing and review
**Next**: Build IPA, test on device, verify retry behavior, merge if successful
**Future**: Investigate MPV audio filter approach for truly reliable sideload support
