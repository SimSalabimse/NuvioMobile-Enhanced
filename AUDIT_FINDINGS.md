# Full Codebase Audit Findings - Nuvio Mobile Enhanced
**Date:** September 25, 2026
**Branch:** `cursor/full-audit-mobile-20260925-d93d`

## Critical Bugs Found

### 1. **SubtitleAutoSyncEngine.kt - Numerical Stability Issues**
**File:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAutoSyncEngine.kt`
**Lines:** 170, 227
**Severity:** Medium
**Issue:** The correlation coefficient calculation can fail with division by zero or NaN when signals have very low variance.
```kotlin
// Line 170: No check for zero variance before division
val confidence = if (stdDev > 0) {
    ((best.second - avgCorrelation) / stdDev).coerceIn(0.0, 1.0)
} else {
    0.0
}

// Line 227: Division by zero possible if sumAudioSq or sumSubSq is zero
return sumProduct / sqrt(sumAudioSq * sumSubSq)
```
**Impact:** Auto-sync can fail or crash with silent audio or very quiet dialogue.
**Fix:** Add safety checks and handle edge cases.

### 2. **iOS MPVPlayerBridge - Device Loss Recovery Race Conditions**
**File:** `iosApp/iosApp/Player/MPVPlayerBridge.swift`
**Lines:** 965-1190
**Severity:** High
**Issue:** Multiple concurrent device loss recovery attempts can occur with inadequate synchronization:
- `isDeviceLossRecoveryPending` and `isRecoveringFromDeviceLoss` are checked and set in different methods without proper locking
- Race between `handleVideoOutputDeviceLost()` and `retryDeviceLossRecoveryNow()`
- `sawDeviceLossInLog` is accessed from both `eventQueue` and main queue without synchronization

**Impact:** GPU device loss can cause app crashes, hangs, or incomplete recovery.
**Fix:** Add proper locking around device loss state transitions.

### 3. **iOS MPVPlayerBridge - Memory Management in Audio Capture**
**File:** `iosApp/iosApp/Player/MPVPlayerBridge.swift`
**Lines:** 1495-1508, 195-205
**Severity:** Medium
**Issue:** `audioCaptureProcessor` is lazily initialized but never explicitly cleaned up. The processor may hold strong references to the view controller creating a retain cycle.

**Impact:** Memory leak during subtitle auto-sync operations.
**Fix:** Add explicit cleanup in `destroyPlayer()`.

### 4. **SyncManager.kt - Concurrency Issues**
**File:** `composeApp/src/commonMain/kotlin/com/nuvio/app/core/sync/SyncManager.kt`
**Lines:** 310-329, 336-376
**Severity:** High
**Issue:** Multiple race conditions in sync coordination:
- `cancelAccountSync()` accesses multiple sync jobs without a single atomic lock
- `foregroundPullJob` can be cancelled while being started in `requestForegroundPull()`
- `activityPullFreshness` and `fullPullFreshness` are read/written in different synchronized blocks

**Impact:** Sync operations can fail silently, duplicate, or leave stale state.
**Fix:** Consolidate state access under a single lock where needed.

### 5. **SkipIntroRepository.kt - Cache Corruption**
**File:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/SkipIntroRepository.kt`
**Lines:** 13-18, 513-518
**Severity:** Medium
**Issue:** The caches (`cache`, `imdbEntriesCache`, `animeSkipShowIdCache`) are plain HashMaps accessed from multiple coroutines without synchronization. `clearCache()` can be called while another coroutine is reading or writing.

**Impact:** Concurrent access can cause crashes, data corruption, or incorrect skip intervals.
**Fix:** Use thread-safe collections or add synchronization.

### 6. **TheIntroDb.kt - Missing Input Validation**
**File:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/TheIntroDb.kt`
**Lines:** 110-162
**Severity:** Low
**Issue:** `submitTimestamp()` doesn't validate input ranges (start/end times, season/episode numbers) before submitting to the API.

**Impact:** Invalid submissions can be sent to TheIntroDB API.
**Fix:** Add validation for reasonable ranges.

### 7. **iOS Player - Position Interpolation Race**
**File:** `iosApp/iosApp/Player/MPVPlayerBridge.swift`
**Lines:** 1840-1848, 1859-1864
**Severity:** Low
**Issue:** `interpolatedPositionSeconds` reads `positionSampleLock` protected state but the calculation happens after unlocking. A concurrent update could make the interpolation inconsistent.

**Impact:** Minor position reporting glitches during playback.
**Fix:** Perform all calculations inside the lock.

## Performance Optimizations

### 8. **SubtitleAutoSyncEngine.kt - Inefficient Signal Generation**
**File:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAutoSyncEngine.kt`
**Lines:** 82-111
**Severity:** Low
**Issue:** `buildAudioEnergyEnvelope()` filters samples in a loop for each window. With large sample counts, this is O(n*m) where n is samples and m is windows.

**Optimization:** Use binary search or maintain a sliding window pointer.

### 9. **iOS MPVPlayerBridge - Excessive Log Coalescing Overhead**
**File:** `iosApp/iosApp/Player/MPVPlayerBridge.swift`
**Lines:** 2119-2151
**Severity:** Low
**Issue:** Every log message creates a string key and locks a dictionary. The coalescing map can grow to 64 entries before being cleared.

**Optimization:** Consider using a ring buffer or time-based bucketing instead of per-message tracking.

### 10. **SkipIntroRepository.kt - Redundant Async Operations**
**File:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/SkipIntroRepository.kt`
**Lines:** 160-186
**Severity:** Low
**Issue:** `getSkipIntervalsForMal()` launches async operations that may not be needed based on earlier results. The MAL-to-IMDB resolution always runs even if AniSkip already has results.

**Optimization:** Cancel unnecessary work early if primary source succeeds.

## Android-Specific Issues

### 11. **Missing Error Handling in ExoPlayer**
**File:** Android player implementation (file too large to read fully)
**Severity:** Medium (requires verification)
**Issue:** Need to verify error handling in Android's ExoPlayer integration, particularly around:
- Network errors during streaming
- Codec failures
- Subtitle loading errors

**Action Required:** Manual inspection of Android player code.

## iOS-Specific Issues

### 12. **Picture-in-Picture State Machine Complexity**
**File:** `iosApp/iosApp/Player/MPVPlayerBridge.swift` and `MPVPlayerViewController+PictureInPicture.swift`
**Severity:** Medium
**Issue:** PiP state machine has many boolean flags (`isPictureInPictureStarting`, `automaticPictureInPicturePrepared`, `automaticPictureInPictureStartArmed`, etc.) that can get out of sync.

**Impact:** PiP can fail to start, get stuck, or trigger incorrectly.
**Fix:** Refactor to explicit state enum.

## Dead Code / Technical Debt

### 13. **Unused Experimental Features**
**Files:** Multiple
**Issue:** Some experimental features may be incomplete or unused:
- `IosExperimentalPictureInPictureSettings`
- Debug build flags scattered throughout

**Action:** Audit for removal or completion.

### 14. **Duplicate Code in Auth Flows**
**Files:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/auth/`
**Issue:** Auth flow has some redundant state management and error handling patterns that could be consolidated.

**Action:** Refactor for better maintainability.

## Configuration Issues

### 15. **Missing Timeout Configurations**
**File:** `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/skip/SkipIntroRepository.kt`
**Lines:** 17-22
**Issue:** Hard-coded timeout values without user configurability. Some APIs might need longer timeouts in poor network conditions.

**Optimization:** Make timeouts configurable or adaptive.

## Summary

**Total Issues Found:** 15
- Critical/High: 4
- Medium: 5
- Low: 6

**Recommended Immediate Fixes:**
1. SubtitleAutoSyncEngine numerical stability (#1)
2. iOS device loss recovery races (#2)
3. SyncManager concurrency (#4)
4. SkipIntroRepository cache safety (#5)

**Recommended Future Improvements:**
- PiP state machine refactoring (#12)
- Performance optimizations (#8, #9, #10)
- Android player error handling audit (#11)
